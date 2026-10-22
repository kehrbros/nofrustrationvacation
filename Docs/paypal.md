# PayPal-Anbindung

- **Modul `com.vacantagent.paypal`:** nur die technische Anbindung an die PayPal REST API. Kennt keine Fachmodule, nur `common` (ArchUnit `PayPalModuleTest`). Einziger Nutzer ist `com.vacantagent.accounting`, siehe [accounting.md](accounting.md).
- **Fassaden:**
  - `PayPalConnect`: „Log in with PayPal“ (OAuth 2.0 Authorization Code). Der Provider meldet sich bei PayPal an und bestätigt sein Konto; wir erhalten `payer_id`, E-Mail und Verifizierungsstatus (`/v1/identity/oauth2/userinfo?schema=paypalv1.1`, Scope `openid email https://uri.paypal.com/services/paypalattributes`). Kein PayPal-Passwort, keine manuell eingetippte E-Mail.
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
  vacantagent.paypal.connect-redirect-uri=http://localhost:5173/paypal/verbunden
  vacantagent.paypal.checkout-return-url=http://localhost:5173/consumer/guthaben/paypal
  vacantagent.paypal.checkout-cancel-url=http://localhost:5173/consumer/guthaben/paypal?abgebrochen=1
  ```

- **Lokal:** Umgebungsvariablen in der IntelliJ-Run-Configuration setzen, oder `backend/config/application-local.properties` anlegen und mit Profil `local` starten (`mvn spring-boot:run -Dspring-boot.run.profiles=local`). `backend/config/` und `.env` sind in `.gitignore`.
- **Produktion:** Secrets der Laufzeitumgebung (z. B. Docker/Kubernetes-Secret) als `PAYPAL_CLIENT_ID`/`PAYPAL_CLIENT_SECRET`, `PAYPAL_ENVIRONMENT=live`.
- Das Secret wird nie geloggt (`PayPalProperties.toString()` maskiert es). Fehlen die Zugangsdaten, startet die App trotzdem; Verknüpfen, Einzahlen und Auszahlen antworten dann mit 503 `PAYPAL_NOT_CONFIGURED`, das Frontend zeigt einen Hinweis.
- Tests rufen PayPal nie auf: Fassaden gemockt, `PayPalClientTest` gegen `MockRestServiceServer`.

## Einrichtung der PayPal-App (Sandbox)

1. developer.paypal.com → Apps & Credentials → Sandbox → App öffnen, Client ID und Secret als Env-Variablen setzen.
2. Features: **Log in with PayPal** aktivieren, Return URL `http://localhost:5173/paypal/verbunden` eintragen (muss exakt `connect-redirect-uri` entsprechen), E-Mail und Kontoinformationen (Payer ID, Verifizierungsstatus) anfordern.
3. Features: **Payouts** aktivieren.
4. Testen mit Sandbox-Personal-Accounts (Zahler und Provider).

## Offen

- Webhooks (`PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.PAYOUTSBATCH.*`) statt Polling; braucht öffentliche URL und Webhook-Id.
- Gebühren: PayPal behält Gebühren bei Einzahlungen ein und berechnet Payouts; wer sie trägt, ist fachlich offen.
