# PayPal integration

- **Module `com.nfv.paypal`:** only the technical integration with the PayPal REST API. Knows no domain modules, only `common` (ArchUnit `PayPalModuleTest`). Only user: `com.nfv.bookings` (payment of alternative accommodations and plans, refunds after cancellation, see [accommodation-alternatives.md](accommodation-alternatives.md#payment)).
- **Facades:**
  - `PayPalCheckout`: payments via the Orders API v2. Create order → payer approves at PayPal (`payer-action` link) → with `intent=CAPTURE` capture, with `intent=AUTHORIZE` first `authorize` (money reserved), then `captureAuthorization` (`final_capture`) or `voidAuthorization` (release, e.g. if the booking fails). `refundCapture` refunds in full. Return and cancel URL per order (`createOrder(…, intent, returnUrl, cancelUrl)`), e.g. back to the sub-route of the alternatives.
- **Removed (2026-11-12):** `PayPalConnect` ("Log in with PayPal") and `PayPalPayouts` (Payouts API) together with the legacy modules `accounting` and `onboarding` of the former token market. Before using them again, restore them from git history (commit before the removal).
- **`client/PayPalHttp`:** app token via client credentials, cached until 60 s before expiry, fetched again once on 401. Errors: 4xx → `PayPalRejectedException` (with `issue`, e.g. `INSTRUMENT_DECLINED`, without effect), 5xx/IO → `PayPalUnavailableException` (outcome unknown).
- **Idempotency:**
  - Every write call carries a `PayPal-Request-Id`, a repetition returns the first result. `ORDER_ALREADY_CAPTURED` → the order is read.
  - Alternatives: `create-stay-{id}`, `authorize-stay-{id}`, `capture-stay-{id}`, `void-stay-{id}`. `ORDER_ALREADY_AUTHORIZED` → the order is read, `AUTHORIZATION_ALREADY_VOIDED` counts as done.

## Configuration and credentials

- **Never commit credentials.** The source is environment variables, `application.properties` only contains placeholders:

  ```properties
  nfv.paypal.environment=${PAYPAL_ENVIRONMENT:sandbox}   # sandbox | live
  nfv.paypal.client-id=${PAYPAL_CLIENT_ID:}
  nfv.paypal.client-secret=${PAYPAL_CLIENT_SECRET:}
  ```

- Return and cancel URLs are passed per order by `bookings` and depend on `nfv.frontend-url` (env `NFV_FRONTEND_URL`, default `http://localhost:18173`), as do login redirect URIs and CORS.

- **Local:** set environment variables in the IntelliJ run configuration, or create `backend/config/application-local.properties` and start with profile `local` (`mvn spring-boot:run -Dspring-boot.run.profiles=local`). `backend/config/` and `.env` are in `.gitignore`.
- **Production:** Docker secrets `nfv_paypal_client_id`/`nfv_paypal_client_secret`, in the container as `/run/secrets/PAYPAL_CLIENT_ID`/`PAYPAL_CLIENT_SECRET` and read via `configtree`, plus `PAYPAL_ENVIRONMENT=live`, see [deployment.md](deployment.md).
- The secret is never logged (`PayPalProperties.toString()` masks it). If the credentials are missing, the app still starts; payments then respond with 503 `PAYPAL_NOT_CONFIGURED`, the frontend shows a notice.
- Tests never call PayPal: facades mocked, `PayPalClientTest` against `MockRestServiceServer`.

## Setting up the PayPal app (sandbox)

1. developer.paypal.com → Apps & Credentials → Sandbox → open the app, set client ID and secret as env variables.
2. Test with a sandbox personal account as payer.

## Local without localhost (sandbox)

- PayPal does not accept `localhost` as return URL, therefore open the frontend via an ngrok tunnel.
- **Docker Compose:** start `ngrok http 18173`, enter the HTTPS URL as `LOCAL_FRONTEND_URL` in `.env`, restart the stack and open the app under the ngrok URL. Without a fixed ngrok host the URL changes with every tunnel start.
- ngrok terminates TLS, nginx detects the browser's scheme via `X-Forwarded-Proto` (`frontend/nginx.conf`).
- The backend accepts only this origin (CORS, OIDC redirect URI), therefore nginx then redirects `http://localhost:18173` to `LOCAL_FRONTEND_URL` (`CANONICAL_FRONTEND_URL`, `frontend/nginx.conf`).
- The backend derives login redirect URIs, CORS and all PayPal URLs from `NFV_FRONTEND_URL`. It stays `http://localhost:18180`; browsers allow calls to `http://localhost` also from HTTPS pages. The tunnel is therefore only usable for the browser on this machine.
- Login session (sessionStorage) and PayPal return must land in the same origin, so do not switch between localhost and the ngrok URL.
- `npm run dev`: `ngrok http 18173` to the Vite server, set `NFV_FRONTEND_URL` (`server.allowedHosts`).

## PayPal button in the sheet

- **Since 2026-11-06** the user pays for an alternative via the PayPal button in the sheet (PayPal JS SDK, `frontend/src/payments/`), no longer via redirect. PayPal opens as a popup over the app and returns to the same page.
  - **Why:** in the installed app (`display: standalone`) a redirect opens PayPal in a browser window outside the app. The return landed there, without login (`sessionStorage`), and the app itself got stuck at payment. The button needs no return URL, hence no ngrok for the return either.
  - Flow: `POST …/alternative-reservations` creates the order (response `payPalOrderId`), the button's `createOrder` returns this id, `onApprove` calls `POST /booking-reservations/{id}/booking` (authorise, book at LiteAPI, capture).
  - Script `https://www.paypal.com/sdk/js?client-id=…&currency=<currency of the reservation>&intent=authorize&components=buttons&commit=true`. The client id is public and comes from `GET /bookings/alternatives/status` (`payPalClientId`, `payPalEnvironment`). There is currently no CSP. If one is added, it needs `www.paypal.com` and `*.paypal.com` for `script-src`, `frame-src` and `connect-src`.
  - Only the PayPal button (`fundingSource: paypal`), because the order is created with `payment_source.paypal`. Card and Pay Later would need an order without `payment_source`.
  - Fallback: if the SDK does not load (offline, blocked), the sheet shows the link "Continue to PayPal" (`approveUrl`, redirect with return as before).
- `nfv.paypal.brand-name` = `NoFrustration.Vacation.` (shown at PayPal).

## Stability

As of 2026-11-06, tested with mocked responses (`AlternativeApiTest`, `LiteApiTest`), not yet against the sandbox.

- **Completion in the background** (`bookings.logic.ReservationRecovery`, every minute, `nfv.bookings.alternatives.recovery-*`): reservations in `PAYMENT_PENDING`, `AUTHORIZED` or `PROVIDER_BOOKED` that no request has worked on for `recovery-grace` (2 min) (`attempted_at`) are continued by the job with the same logic as `POST /booking-reservations/{id}/booking`. `PAYMENT_PENDING` only if the order is `APPROVED` at PayPal. Payments not approved fail after `approval-timeout` (3 h). Due refunds are retried. This way no money stays reserved if the user closes the app after approval.
- **Duplicate booking (LiteAPI `4005`)**: the response of an earlier booking was lost. The booking is looked up via `GET /bookings?clientReference=nfv-stay-<id>` and continued. Field names of the response are not described in the docs; `data[].bookingId`, `clientReference`, `hotelConfirmationCode` are read; check with the sandbox.
- **Capture declined** after a successful booking: reservation `NEEDS_ATTENTION` instead of a booking without payment. The frontend shows "Booked, payment is being checked", the case must be resolved by hand (log `ERROR`). PayPal unreachable at capture: stays `PROVIDER_BOOKED`, the job tries again.
- **Open reservations visible**: `GET /trips/{tripId}/booking-reservations`, banner above the map (`OpenReservations`), polls every 15 s and reloads the trip when one has finished.
- **Cancellation with refund**: `POST /bookings/{bookingId}/refund-cancellation` for alternatives booked in the app: cancellation at LiteAPI (`PUT /bookings/{id}`), with `CANCELLED` full refund (including our commission), with `CANCELLED_WITH_CHARGES` the amount stated by LiteAPI (field `refund_amount`/`refundAmount`, otherwise nothing, note in `failure`). Refund idempotent via `refund-stay-<id>`, if it fails, the job refunds later.

## Open

- Webhooks (`PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.CAPTURE.REFUNDED`) instead of polling; needs a public URL and webhook id.
- Fees: PayPal withholds fees on payments; who bears them is an open domain question.
