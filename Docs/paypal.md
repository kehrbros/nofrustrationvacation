# PayPal-Anbindung

- **Modul `com.vacantagent.paypal`:** nur die technische Anbindung an die PayPal REST API. Kennt keine Fachmodule, nur `common` (ArchUnit `PayPalModuleTest`). Einziger Nutzer ist `com.vacantagent.accounting`, siehe [accounting.md](accounting.md).
- **Fassaden:**
  - `PayPalConnect`: „Log in with PayPal“ (OAuth 2.0 Authorization Code). Der Provider meldet sich bei PayPal an und bestätigt sein Konto; wir erhalten `payer_id`, E-Mail und Verifizierungsstatus (Code-Tausch über `/v1/identity/openidconnect/tokenservice`, dann `/v1/oauth2/token/userinfo?schema=openid`, wie in PayPals Doku zu Payouts-Empfängern; der Tausch über `/v1/oauth2/token` mit `schema=paypalv1.1` scheiterte in der Sandbox mit `400 invalid_request`, Scope `openid email https://uri.paypal.com/services/paypalattributes`). Kein PayPal-Passwort, keine manuell eingetippte E-Mail.
  - `PayPalCheckout`: Einzahlungen über die Orders API v2 (`intent=CAPTURE`): Order anlegen → Zahler bestätigt bei PayPal (`payer-action`-Link) → Capture.
  - `PayPalPayouts`: Auszahlungen über die Payouts API an die `payer_id` (`recipient_type=PAYPAL_ID`). Asynchron: Status später per `GET /v1/payments/payouts/{batchId}`.
- **`client/PayPalHttp`:** App-Token per Client Credentials, gecacht bis 60 s vor Ablauf, bei 401 einmal neu geholt. Fehler: 4xx → `PayPalRejectedException` (mit `issue`, z. B. `INSTRUMENT_DECLINED`, ohne Wirkung), 5xx/IO → `PayPalUnavailableException` (Ausgang unbekannt).
- **Idempotenz:**
  - Order anlegen und Capture mit `PayPal-Request-Id` (`create-top-up-{id}`, `capture-top-up-{id}`), Wiederholung liefert das erste Ergebnis. `ORDER_ALREADY_CAPTURED` → Order wird gelesen.
  - Payouts mit `sender_batch_id = vacantagent-payout-{id}`, PayPal lehnt eine zweite Batch mit derselben Id ab (30 Tage).

## Konfiguration und Zugangsdaten

- **Zugangsdaten nie committen.** Quelle sind Umgebungsvariablen, `application.properties` enthält nur Platzhalter:

  ```properties
  vacantagent.paypal.environment=${PAYPAL_ENVIRONMENT:sandbox}   # sandbox | live
  vacantagent.paypal.client-id=${PAYPAL_CLIENT_ID:}
  vacantagent.paypal.client-secret=${PAYPAL_CLIENT_SECRET:}
  vacantagent.paypal.connect-redirect-uri=${vacantagent.frontend-url}/paypal/connected
  vacantagent.paypal.checkout-return-url=${vacantagent.frontend-url}/consumer/guthaben/paypal
  vacantagent.paypal.checkout-cancel-url=${vacantagent.frontend-url}/consumer/guthaben/paypal?abgebrochen=1
  ```

- Alle Return-URLs hängen an `vacantagent.frontend-url` (Env `VACANTAGENT_FRONTEND_URL`, Standard `http://localhost:5173`), ebenso CORS. Sie zeigen auf Seiten des früheren Web-Frontends, das durch die App ersetzt ist; für die App bräuchte es App Links / Universal Links (offen).

- **Lokal:** Umgebungsvariablen in der IntelliJ-Run-Configuration setzen, oder `backend/config/application-local.properties` anlegen und mit Profil `local` starten (`mvn spring-boot:run -Dspring-boot.run.profiles=local`). `backend/config/` und `.env` sind in `.gitignore`.
- **Produktion:** Docker Secrets `vacantagent_paypal_client_id`/`vacantagent_paypal_client_secret`, im Container als `/run/secrets/PAYPAL_CLIENT_ID`/`PAYPAL_CLIENT_SECRET` und per `configtree` eingelesen, dazu `PAYPAL_ENVIRONMENT=live`, siehe [deployment.md](deployment.md).
- Das Secret wird nie geloggt (`PayPalProperties.toString()` maskiert es). Fehlen die Zugangsdaten, startet die App trotzdem; Verknüpfen, Einzahlen und Auszahlen antworten dann mit 503 `PAYPAL_NOT_CONFIGURED`, das Frontend zeigt einen Hinweis.
- Tests rufen PayPal nie auf: Fassaden gemockt, `PayPalClientTest` gegen `MockRestServiceServer`.

## Einrichtung der PayPal-App (Sandbox)

1. developer.paypal.com → Apps & Credentials → Sandbox → App öffnen, Client ID und Secret als Env-Variablen setzen.
2. Features: **Log in with PayPal** aktivieren, Return URL `${VACANTAGENT_FRONTEND_URL}/paypal/connected` eintragen (muss exakt `connect-redirect-uri` entsprechen), lokal also `https://localtest.me:5443/paypal/connected`, siehe unten. Unter „Advanced options“ → „Information requested from customers“ **E-Mail**, **PayPal account ID (payer ID)** und **Account verification status** anhaken und speichern. Fehlen sie, lehnt PayPal den Userinfo-Abruf nach der Bestätigung mit `400 invalid_request` „Unable to fetch details to serve the request“ ab (Frontend: 502 `PAYPAL_ERROR`); das Backend loggt dann angefragte und gewährte Scopes.
3. Features: **Payouts** aktivieren.
4. Testen mit Sandbox-Personal-Accounts (Zahler und Provider).

## Lokal ohne localhost (Sandbox)

- PayPal akzeptiert `localhost` nicht als Return URL. Ein Tunnel (ngrok) ist trotzdem unnötig: PayPal ruft die URL nie selbst auf, nur der Browser wird dorthin umgeleitet.
- Stattdessen einen Hostnamen nehmen, der öffentlich auf `127.0.0.1` auflöst, z. B. `localtest.me` (Alternative `127.0.0.1.nip.io`).
- **Nur mit HTTPS:** Außer `localhost` gilt eine `http`-Seite im Browser nicht als sicherer Kontext, dann fehlt `crypto.subtle` und der Login (PKCE) scheitert.
- Das HTTPS-Setup dafür (nginx mit selbstsigniertem Zertifikat auf `https://localtest.me:5443`, Service `local-cert`) gehörte zum Web-Frontend und ist mit ihm entfernt. `VACANTAGENT_FRONTEND_URL` bzw. `LOCAL_FRONTEND_URL` steuern weiter die PayPal-URLs.

## Offen

- Webhooks (`PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.PAYOUTSBATCH.*`) statt Polling; braucht öffentliche URL und Webhook-Id.
- Gebühren: PayPal behält Gebühren bei Einzahlungen ein und berechnet Payouts; wer sie trägt, ist fachlich offen.
