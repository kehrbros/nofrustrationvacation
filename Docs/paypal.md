# PayPal integration

- **Module `com.nfv.paypal`:** only the technical integration with the PayPal REST API. Knows no domain modules, only `common` (ArchUnit `PayPalModuleTest`). Users: `com.nfv.bookings` (payment of alternative accommodations, see [accommodation-alternatives.md](accommodation-alternatives.md#payment)) and the legacy code `com.nfv.accounting`, see [accounting.md](accounting.md).
- **Facades:**
  - `PayPalConnect`: "Log in with PayPal" (OAuth 2.0 authorization code). The provider logs in at PayPal and confirms their account; we receive `payer_id`, email and verification status (code exchange via `/v1/identity/openidconnect/tokenservice`, then `/v1/oauth2/token/userinfo?schema=openid`, as in PayPal's docs on payouts recipients; the exchange via `/v1/oauth2/token` with `schema=paypalv1.1` failed in the sandbox with `400 invalid_request`, scope `openid email https://uri.paypal.com/services/paypalattributes`). No PayPal password, no manually typed email.
  - `PayPalCheckout`: payments via the Orders API v2. Create order → payer approves at PayPal (`payer-action` link) → with `intent=CAPTURE` capture (top-ups in `accounting`, return URLs from the configuration), with `intent=AUTHORIZE` first `authorize` (money reserved), then `captureAuthorization` (`final_capture`) or `voidAuthorization` (release, e.g. if the booking fails). `refundCapture` refunds in full. Return and cancel URL per order (`createOrder(…, intent, returnUrl, cancelUrl)`), e.g. back to the sub-route of the alternatives.
  - `PayPalPayouts`: payouts via the Payouts API to the `payer_id` (`recipient_type=PAYPAL_ID`). Asynchronous: status later via `GET /v1/payments/payouts/{batchId}`.
- **`client/PayPalHttp`:** app token via client credentials, cached until 60 s before expiry, fetched again once on 401. Errors: 4xx → `PayPalRejectedException` (with `issue`, e.g. `INSTRUMENT_DECLINED`, without effect), 5xx/IO → `PayPalUnavailableException` (outcome unknown).
- **Idempotency:**
  - Create order and capture with `PayPal-Request-Id` (`create-top-up-{id}`, `capture-top-up-{id}`), a repetition returns the first result. `ORDER_ALREADY_CAPTURED` → the order is read.
  - Alternatives: `create-stay-{id}`, `authorize-stay-{id}`, `capture-stay-{id}`, `void-stay-{id}`. `ORDER_ALREADY_AUTHORIZED` → the order is read, `AUTHORIZATION_ALREADY_VOIDED` counts as done.
  - Payouts with `sender_batch_id = nfv-payout-{id}`, PayPal rejects a second batch with the same id (30 days).

## Configuration and credentials

- **Never commit credentials.** The source is environment variables, `application.properties` only contains placeholders:

  ```properties
  nfv.paypal.environment=${PAYPAL_ENVIRONMENT:sandbox}   # sandbox | live
  nfv.paypal.client-id=${PAYPAL_CLIENT_ID:}
  nfv.paypal.client-secret=${PAYPAL_CLIENT_SECRET:}
  nfv.paypal.connect-redirect-uri=${nfv.frontend-url}/paypal/connected
  nfv.paypal.checkout-return-url=${nfv.frontend-url}/consumer/guthaben/paypal
  nfv.paypal.checkout-cancel-url=${nfv.frontend-url}/consumer/guthaben/paypal?abgebrochen=1
  ```

- All return URLs depend on `nfv.frontend-url` (env `NFV_FRONTEND_URL`, default `http://localhost:18173`), as do login redirect URIs and CORS. The frontend pages behind the PayPal URLs (`/paypal/connected`, `/consumer/guthaben/paypal`) have been removed.

- **Local:** set environment variables in the IntelliJ run configuration, or create `backend/config/application-local.properties` and start with profile `local` (`mvn spring-boot:run -Dspring-boot.run.profiles=local`). `backend/config/` and `.env` are in `.gitignore`.
- **Production:** Docker secrets `nfv_paypal_client_id`/`nfv_paypal_client_secret`, in the container as `/run/secrets/PAYPAL_CLIENT_ID`/`PAYPAL_CLIENT_SECRET` and read via `configtree`, plus `PAYPAL_ENVIRONMENT=live`, see [deployment.md](deployment.md).
- The secret is never logged (`PayPalProperties.toString()` masks it). If the credentials are missing, the app still starts; linking, top-up and payout then respond with 503 `PAYPAL_NOT_CONFIGURED`, the frontend shows a notice.
- Tests never call PayPal: facades mocked, `PayPalClientTest` against `MockRestServiceServer`.

## Setting up the PayPal app (sandbox)

1. developer.paypal.com → Apps & Credentials → Sandbox → open the app, set client ID and secret as env variables.
2. Features: enable **Log in with PayPal**, enter return URL `${NFV_FRONTEND_URL}/paypal/connected` (must match `connect-redirect-uri` exactly), locally therefore `https://<ngrok-host>/paypal/connected`, see below. Under "Advanced options" → "Information requested from customers" tick **Email**, **PayPal account ID (payer ID)** and **Account verification status** and save. If they are missing, PayPal rejects the userinfo request after approval with `400 invalid_request` "Unable to fetch details to serve the request" (frontend: 502 `PAYPAL_ERROR`); the backend then logs requested and granted scopes.
3. Features: enable **Payouts**.
4. Test with sandbox personal accounts (payer and provider).

## Local without localhost (sandbox)

- PayPal does not accept `localhost` as return URL, therefore open the frontend via an ngrok tunnel.
- **Docker Compose:** start `ngrok http 18173`, enter the HTTPS URL as `LOCAL_FRONTEND_URL` in `.env`, restart the stack and open the app under the ngrok URL. Set the return URL in the PayPal app to `https://<ngrok-host>/paypal/connected`; without a fixed ngrok host both change with every tunnel start.
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

- Webhooks (`PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.PAYOUTSBATCH.*`) instead of polling; needs a public URL and webhook id.
- Fees: PayPal withholds fees on top-ups and charges for payouts; who bears them is an open domain question.
