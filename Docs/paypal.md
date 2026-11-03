# PayPal-Anbindung

- **Modul `com.nfv.paypal`:** nur die technische Anbindung an die PayPal REST API. Kennt keine Fachmodule, nur `common` (ArchUnit `PayPalModuleTest`). Nutzer: `com.nfv.bookings` (Zahlung alternativer Unterkünfte, siehe [accommodation-alternatives.md](accommodation-alternatives.md#zahlung)) und der Altbestand `com.nfv.accounting`, siehe [accounting.md](accounting.md).
- **Fassaden:**
  - `PayPalConnect`: „Log in with PayPal“ (OAuth 2.0 Authorization Code). Der Provider meldet sich bei PayPal an und bestätigt sein Konto; wir erhalten `payer_id`, E-Mail und Verifizierungsstatus (Code-Tausch über `/v1/identity/openidconnect/tokenservice`, dann `/v1/oauth2/token/userinfo?schema=openid`, wie in PayPals Doku zu Payouts-Empfängern; der Tausch über `/v1/oauth2/token` mit `schema=paypalv1.1` scheiterte in der Sandbox mit `400 invalid_request`, Scope `openid email https://uri.paypal.com/services/paypalattributes`). Kein PayPal-Passwort, keine manuell eingetippte E-Mail.
  - `PayPalCheckout`: Zahlungen über die Orders API v2. Order anlegen → Zahler bestätigt bei PayPal (`payer-action`-Link) → bei `intent=CAPTURE` Capture (Einzahlungen in `accounting`, Return-URLs aus der Konfiguration), bei `intent=AUTHORIZE` erst `authorize` (Geld reserviert), dann `captureAuthorization` (`final_capture`) oder `voidAuthorization` (freigeben, z. B. wenn die Buchung scheitert). `refundCapture` erstattet vollständig. Return- und Cancel-URL je Order (`createOrder(…, intent, returnUrl, cancelUrl)`), z. B. zurück auf die Unterroute der Alternativen.
  - `PayPalPayouts`: Auszahlungen über die Payouts API an die `payer_id` (`recipient_type=PAYPAL_ID`). Asynchron: Status später per `GET /v1/payments/payouts/{batchId}`.
- **`client/PayPalHttp`:** App-Token per Client Credentials, gecacht bis 60 s vor Ablauf, bei 401 einmal neu geholt. Fehler: 4xx → `PayPalRejectedException` (mit `issue`, z. B. `INSTRUMENT_DECLINED`, ohne Wirkung), 5xx/IO → `PayPalUnavailableException` (Ausgang unbekannt).
- **Idempotenz:**
  - Order anlegen und Capture mit `PayPal-Request-Id` (`create-top-up-{id}`, `capture-top-up-{id}`), Wiederholung liefert das erste Ergebnis. `ORDER_ALREADY_CAPTURED` → Order wird gelesen.
  - Alternativen: `create-stay-{id}`, `authorize-stay-{id}`, `capture-stay-{id}`, `void-stay-{id}`. `ORDER_ALREADY_AUTHORIZED` → Order wird gelesen, `AUTHORIZATION_ALREADY_VOIDED` gilt als erledigt.
  - Payouts mit `sender_batch_id = nfv-payout-{id}`, PayPal lehnt eine zweite Batch mit derselben Id ab (30 Tage).

## Konfiguration und Zugangsdaten

- **Zugangsdaten nie committen.** Quelle sind Umgebungsvariablen, `application.properties` enthält nur Platzhalter:

  ```properties
  nfv.paypal.environment=${PAYPAL_ENVIRONMENT:sandbox}   # sandbox | live
  nfv.paypal.client-id=${PAYPAL_CLIENT_ID:}
  nfv.paypal.client-secret=${PAYPAL_CLIENT_SECRET:}
  nfv.paypal.connect-redirect-uri=${nfv.frontend-url}/paypal/connected
  nfv.paypal.checkout-return-url=${nfv.frontend-url}/consumer/guthaben/paypal
  nfv.paypal.checkout-cancel-url=${nfv.frontend-url}/consumer/guthaben/paypal?abgebrochen=1
  ```

- Alle Return-URLs hängen an `nfv.frontend-url` (Env `NFV_FRONTEND_URL`, Standard `http://localhost:5173`), ebenso Login-Redirect-URIs und CORS. Die Frontend-Seiten hinter den PayPal-URLs (`/paypal/connected`, `/consumer/guthaben/paypal`) sind entfernt.

- **Lokal:** Umgebungsvariablen in der IntelliJ-Run-Configuration setzen, oder `backend/config/application-local.properties` anlegen und mit Profil `local` starten (`mvn spring-boot:run -Dspring-boot.run.profiles=local`). `backend/config/` und `.env` sind in `.gitignore`.
- **Produktion:** Docker Secrets `nfv_paypal_client_id`/`nfv_paypal_client_secret`, im Container als `/run/secrets/PAYPAL_CLIENT_ID`/`PAYPAL_CLIENT_SECRET` und per `configtree` eingelesen, dazu `PAYPAL_ENVIRONMENT=live`, siehe [deployment.md](deployment.md).
- Das Secret wird nie geloggt (`PayPalProperties.toString()` maskiert es). Fehlen die Zugangsdaten, startet die App trotzdem; Verknüpfen, Einzahlen und Auszahlen antworten dann mit 503 `PAYPAL_NOT_CONFIGURED`, das Frontend zeigt einen Hinweis.
- Tests rufen PayPal nie auf: Fassaden gemockt, `PayPalClientTest` gegen `MockRestServiceServer`.

## Einrichtung der PayPal-App (Sandbox)

1. developer.paypal.com → Apps & Credentials → Sandbox → App öffnen, Client ID und Secret als Env-Variablen setzen.
2. Features: **Log in with PayPal** aktivieren, Return URL `${NFV_FRONTEND_URL}/paypal/connected` eintragen (muss exakt `connect-redirect-uri` entsprechen), lokal also `https://<ngrok-host>/paypal/connected`, siehe unten. Unter „Advanced options“ → „Information requested from customers“ **E-Mail**, **PayPal account ID (payer ID)** und **Account verification status** anhaken und speichern. Fehlen sie, lehnt PayPal den Userinfo-Abruf nach der Bestätigung mit `400 invalid_request` „Unable to fetch details to serve the request“ ab (Frontend: 502 `PAYPAL_ERROR`); das Backend loggt dann angefragte und gewährte Scopes.
3. Features: **Payouts** aktivieren.
4. Testen mit Sandbox-Personal-Accounts (Zahler und Provider).

## Lokal ohne localhost (Sandbox)

- PayPal akzeptiert `localhost` nicht als Return URL, deshalb das Frontend über einen ngrok-Tunnel aufrufen.
- **Docker Compose:** `ngrok http 5173` starten, die HTTPS-URL als `LOCAL_FRONTEND_URL` in die `.env` eintragen, Stack neu starten und die App unter der ngrok-URL öffnen. Die Return URL in der PayPal-App auf `https://<ngrok-host>/paypal/connected` setzen; ohne festen ngrok-Host ändert sich beides bei jedem Tunnelstart.
- ngrok terminiert TLS, nginx erkennt das Schema des Browsers über `X-Forwarded-Proto` (`frontend/nginx.conf`).
- Das Backend akzeptiert nur diesen Origin (CORS, OIDC-Redirect-URI), deshalb leitet nginx `http://localhost:5173` dann auf `LOCAL_FRONTEND_URL` um (`CANONICAL_FRONTEND_URL`, `frontend/nginx.conf`).
- Das Backend leitet aus `NFV_FRONTEND_URL` Login-Redirect-URIs, CORS und alle PayPal-URLs ab. Es bleibt `http://localhost:8080`; Browser erlauben Aufrufe an `http://localhost` auch von HTTPS-Seiten. Der Tunnel taugt deshalb nur für den Browser auf diesem Rechner.
- Login-Session (sessionStorage) und PayPal-Rückkehr müssen im selben Origin landen, also nicht zwischen localhost und der ngrok-URL wechseln.
- `npm run dev`: `ngrok http 5173` auf den Vite-Server, `NFV_FRONTEND_URL` setzen (`server.allowedHosts`).

## Offen

- Webhooks (`PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.PAYOUTSBATCH.*`) statt Polling; braucht öffentliche URL und Webhook-Id.
- Gebühren: PayPal behält Gebühren bei Einzahlungen ein und berechnet Payouts; wer sie trägt, ist fachlich offen.
