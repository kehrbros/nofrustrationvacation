# Abrechnung

- **Modul `com.vacantagent.accounting`:** fachliche Abrechnung (Konten, Buchungen, Einzahlung, Auszahlung, Nutzungsabrechnung). Nutzt `com.vacantagent.paypal` für die Zahlungen, siehe [paypal.md](paypal.md). Beträge immer `Money` in USD, siehe [money.md](money.md).

## Konten und Buchungen

- **Zwei Konten je Nutzer** (`Account`, Tabelle `accounting_account`, eindeutig je `app_user_id` und `type`):
  - `CREDIT` (Guthaben, Consumer): per PayPal aufgeladen, für LLM-Nutzung verbraucht, **nicht auszahlbar**.
  - `EARNINGS` (Einnahmen, Provider): aus verkauften Tokens, per PayPal auszahlbar.
  - Grund für die Trennung: eingezahltes Geld soll nicht direkt wieder ausgezahlt werden können (Gebühren, Geldwäsche-Risiko).
- **Ersetzt `app_user.account_balance`:** `V11__accounting.sql` übernimmt vorhandene Guthaben als `CREDIT` mit Buchung `OPENING_BALANCE` und löscht die Spalte. `GET /api/v1/users/me` liefert kein `accountBalance` mehr, Kontostände kommen aus `GET /api/v1/accounting/balances`.
- **Buchungen** (`LedgerEntry`): nur anhängen, nie ändern; Summe der Buchungen = Kontostand. Typen: `TOP_UP`, `PAYOUT`, `PAYOUT_REVERSAL`, `USAGE_CHARGE`, `USAGE_EARNING`, `OPENING_BALANCE`. `referenceId` zeigt auf Aufladung, Auszahlung oder Vereinbarung.
- **`AccountLedger`:** `credit`/`debit` nur innerhalb einer Transaktion (`MANDATORY`). Der Kontostand ändert sich mit einem atomaren `UPDATE … SET balance = balance + :delta WHERE … AND balance + :delta >= 0`: keine verlorenen Updates, nie negativ, keine Locks (Test mit parallelen Abbuchungen). Ausnahme `debitAllowingNegative`: nur für `CREDIT`, ohne Untergrenze, für bereits gelieferte LLM-Nutzung. Zusätzlich DB-Constraint `type = 'CREDIT' OR balance >= 0` (`V14__credit_may_go_negative.sql`).

## Auszahlungskonto verknüpfen (Provider)

- `PayoutAccount` (auditiert): `payer_id` von PayPal, E-Mail, verifiziert ja/nein. Ein PayPal-Konto gehört höchstens zu einem Nutzer (`PAYPAL_ACCOUNT_IN_USE`).
- Ablauf:
  1. `POST /api/v1/accounting/payout-account/paypal/authorization` → zufälliger `state` (32 Byte) wird für den Nutzer gespeichert, Antwort ist die PayPal-Login-URL.
  2. PayPal leitet auf `/paypal/connected?code=…&state=…` (Frontend) zurück.
  3. `POST /api/v1/accounting/payout-account/paypal {code, state}`: `state` muss vom selben Nutzer sein, ist 15 min gültig (`link-state-ttl`) und nur einmal verwendbar (CSRF-Schutz). Danach Code gegen Kontodaten tauschen.
- `DELETE /api/v1/accounting/payout-account` trennt, solange keine Auszahlung offen ist (`PAYOUTS_OPEN`).
- Teil des Provider-Onboardings, siehe [onboarding.md](onboarding.md).

## Einzahlung (Consumer)

1. `POST /api/v1/accounting/top-ups {amount: {amount, currency}}`: Betrag in einer unterstützten Währung mit höchstens deren Nachkommastellen. Umrechnung mit `toBaseForBooking` (Kurs höchstens 4 Tage alt), Originalbetrag und Kurs werden gespeichert (`OriginalAmount`). Grenzen 5–1000 USD (`min-top-up`, `max-top-up`). PayPal-Order über den Originalbetrag.
2. Frontend leitet auf `approveUrl`, PayPal kommt zurück auf `/consumer/guthaben/paypal?token={orderId}` (abgebrochen: `&abgebrochen=1`).
3. `POST /top-ups/paypal/{orderId}/capture`: Gutschrift erst bei Capture `COMPLETED` und passendem Betrag, genau einmal (Zeile gesperrt, Status geprüft). `PENDING` schreibt der Poller später gut. Abgelehnt → `FAILED`, `PAYMENT_DECLINED`.
- Status `TopUp`: `CREATED` → `COMPLETED` | `PENDING` → `COMPLETED` | `CANCELLED` | `FAILED`.

## Auszahlung (Provider)

1. `POST /api/v1/accounting/payouts {currency, amount?}`: ohne `amount` alles (Einnahmen in der Zielwährung abgerundet, `MoneyRounding.PAYOUT`). Mindestens 10 USD (`min-payout`), Auszahlungskonto nötig.
2. In einer Transaktion: `Payout` (`REQUESTED`) anlegen und `EARNINGS` belasten. Erst danach PayPal aufrufen, damit nie doppelt ausgezahlt wird.
3. PayPal-Antwort: angenommen → `SUBMITTED`; abgelehnt → `FAILED` + Rückbuchung `PAYOUT_REVERSAL`; Ausgang unbekannt (Timeout/5xx) → `UNKNOWN`, bleibt belastet, Fehler im Log mit `sender_batch_id` zur manuellen Prüfung im PayPal-Dashboard.
4. `PaymentStatusPoller` (alle 5 min, `status-check-interval`): `SUCCESS` → `COMPLETED`; `FAILED`, `RETURNED`, `REFUNDED`, `REVERSED`, `BLOCKED`, `DENIED` → `FAILED` + Rückbuchung (genau einmal).

## Nutzungsabrechnung

- `UsageBilling.settle(agreementId, consumer, provider, cost)`: belastet `CREDIT` des Consumers und schreibt `EARNINGS` des Providers gut, beides oder nichts. Die Nutzung ist schon geliefert, deshalb darf `CREDIT` dabei negativ werden; weitere Prompts lehnt `LlmLogic` ab, bis das Guthaben wieder positiv ist. Aufrufer ist `LlmLogic`, siehe [llm.md](llm.md).

## API-Fehler

- Problem Details mit stabilem Feld `code`: `INVALID_AMOUNT`, `LINK_STATE_INVALID` (400), `INSUFFICIENT_BALANCE`, `PAYOUT_ACCOUNT_REQUIRED`, `PAYPAL_ACCOUNT_IN_USE`, `PAYOUTS_OPEN` (409), `AMOUNT_OUT_OF_RANGE`, `PAYMENT_NOT_APPROVED`, `PAYMENT_DECLINED` (422), `TOP_UP_NOT_FOUND` (404), `PAYPAL_ERROR` (502), `PAYPAL_NOT_CONFIGURED`, `EXCHANGE_RATE_UNAVAILABLE` (503). Deutsche Texte im Frontend (`src/accounting/problems.ts`).

## Konfiguration

```properties
vacantagent.accounting.min-top-up=5.00
vacantagent.accounting.max-top-up=1000.00
vacantagent.accounting.min-payout=10.00
vacantagent.accounting.link-state-ttl=15m
vacantagent.accounting.status-check-enabled=true   # in Tests false
vacantagent.accounting.status-check-interval=5m
```

## Tests

- `AccountingFlowTest` (Einzahlung, Auszahlung, Verknüpfung, Nutzung über die API, PayPal gemockt), `AccountLedgerTest` (nie negativ, parallele Abbuchungen), `PayPalClientTest`, `PayPalModuleTest`.

## Offen

- Plattformgebühr und PayPal-Gebühren.
- Abgelaufene `CREATED`-Aufladungen aufräumen; `UNKNOWN`-Auszahlungen im Betreiber-UI auflösen.
- Pflicht zu verifiziertem PayPal-Konto (wird gespeichert, aber nicht erzwungen).
