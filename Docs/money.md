# Geldbeträge und Währungen

- **Grundsatz:** Logik, Persistenz und Abrechnung rechnen ausschließlich in der Basiswährung. Fremdwährungen existieren nur an der API (`api`/`dto`) und im Modul `com.nfv.currency`.
- **Basiswährung: USD**, fest in `Money.BASE_CURRENCY`. Nicht konfigurierbar: ein Wechsel erfordert eine Datenmigration.
- **Warum nur eine Währung:** Vergleiche und Summen sind ohne Kurs eindeutig, die Envers-Historie bleibt kursunabhängig.

## Typ `Money` (`com.nfv.common.money`)

- `record Money(BigDecimal amount)`, immer Basiswährung, daher ohne Währungsfeld. Für alle Geldbeträge in Entities, Logik und Repositories, nie rohe `BigDecimal`.
- **Skala:** fest 8 Nachkommastellen (`Money.SCALE`), im Konstruktor per `HALF_EVEN` normiert, daher ist `equals` sicher.
- **Operationen:** `plus`, `minus`, `times`, `dividedBy` (intern `MathContext.DECIMAL128`), `isNegative`, `isZero`, `isGreaterThan`, `isLessThan`, `compareTo`, `Money.ZERO`, `Money.of(...)`. Nie `double`.
- **Fremdwährungsbetrag:** `record CurrencyAmount(BigDecimal amount, Currency currency)`, nur an der API und im Modul `currency`.

## Ausnahme: Preise von Buchungen

- `Booking.price` + `currency` (Modul `bookings`) ist der Preis, wie er in der Bestätigung steht (z. B. 412,50 EUR), in beliebiger Währung, `NUMERIC(12, 2)`. Er ist nur Information für den Nutzer, die App rechnet und rechnet nicht um. Eine Umrechnung in die Basiswährung würde den gebuchten Betrag verfälschen.
- Ebenso `BookingReservation` (Tabelle `booking_reservation`, Buchung einer Alternative über LiteAPI): Nettopreis, Provision und Endpreis in der Währung des Angebots, so an PayPal übergeben, keine Umrechnung, siehe [accommodation-alternatives.md](accommodation-alternatives.md#zahlung). Gerechnet wird nur Nettopreis + Provision (`Pricing`).
- Ebenso das Angebot eines Vorschlags des Agenten (`advisor_proposal.stay_price`, `ProposalStay`): der Preis, den die Suche geliefert hat, in der Währung des Angebots, beim Buchen wird er neu geprüft, siehe [advisor.md](advisor.md#search_accommodations).
- Ebenso Pläne (`V50`): Angebote der Schritte (`advisor_proposal_step.stay_price`), Mehrkosten des Plans (`advisor_proposal.extra_cost`) und die gemeinsame Zahlung (`PlanPayment.total`, Tabelle `plan_payment`, Summe der Reservierungen in ihrer einen Währung), siehe [incident-plans.md](incident-plans.md).
- `MoneyArchitectureTest` und `MoneyPersistenceTest` nehmen `Booking.price`, `BookingReservation`, `PlanPayment` bzw. die Tabellen `booking`, `booking_aud`, `booking_reservation`, `advisor_proposal`, `advisor_proposal_step`, `plan_payment` ausdrücklich aus (`ProposalStay` ist kein `@Entity`, fällt also nicht unter `MoneyArchitectureTest`).

## Persistenz

- **Mapping:** `common.data.MoneyConverter` (`@Converter(autoApply = true)`, analog `DurationConverter`). Eine Spalte pro Betrag, keine Währungsspalte. Entity-Feld: `@Column(precision = 19, scale = Money.SCALE) private Money ...;`
- **Spaltentyp:** `NUMERIC(19, 8)`. Neue Geldspalten in Migrationen ebenso anlegen, in `_aud`-Tabellen auch.
- **Umgestellt durch `V4__money.sql`** (vorher Entities `scale = 6`, Schema `NUMERIC(38, 2)`):
  - Preise von Markt, Angeboten und Aufträgen (entfernt mit `V18__drop_market_and_llm.sql`)
  - `AppUser.accountBalance` (seit `V11__accounting.sql` ersetzt durch `accounting_account.balance`), zusätzlich neues Feld `AppUser.preferredCurrency`
- **Buchungen mit Umrechnung** (Ein-/Auszahlung, siehe [accounting.md](accounting.md)): `CurrencyConversionService.toBaseForBooking(...)` liefert eine `CurrencyConversion` (Originalbetrag, Basisbetrag, Kurs, Kursdatum). In der API zu `common.money.OriginalAmount` (`@Embeddable`, Spalten `original_amount`, `original_currency`, `exchange_rate`, `exchange_rate_date`) gemappt und mit der Buchung gespeichert, nie neu berechnet.

## Wechselkurse (`com.nfv.currency`)

- **Quelle:** `EcbExchangeRateProvider`, EZB-Referenzkurse (ohne API-Key, werktags ~16:00 MEZ, Basis EUR). Kurse zur Basiswährung als Kreuzkurs `EUR→X / EUR→USD`. Andere Quelle: weitere Implementierung von `ExchangeRateProvider`.
- **Speicherung:** Tabelle `exchange_rate(currency, rate NUMERIC(19, 10), rate_date, fetched_at, source)`, eindeutig auf `(currency, rate_date)`. `rate` = Einheiten Fremdwährung je 1 USD. Nicht auditiert, die Tabelle ist selbst die Historie.
- **Abruf:** `ExchangeRateUpdater` beim Start und per Cron (`nfv.money.exchange-rates.update-cron`, Mo–Fr 17:00 Europe/Berlin). Fehler werden nur geloggt, es gilt dann der letzte gespeicherte Kurs. Abschaltbar mit `nfv.money.exchange-rates.update-enabled=false` (in Tests gesetzt über `src/test/resources/config/application.properties`).
- **Cache:** neuester Kurs je Währung im `CurrencyConversionService`, nach jedem Abruf geleert.
- **Veraltete Kurse:** Anzeige nutzt immer den letzten Kurs (Wochenende/Feiertage). Buchungswirksame Umrechnung (`toBaseForBooking`) lehnt Kurse älter als `nfv.money.exchange-rates.max-age-days` (4) mit `ExchangeRateUnavailableException` ab.
- **Unterstützte Währungen:** `nfv.money.currencies=USD,EUR,GBP,CHF`. Andere Währung → `UnsupportedCurrencyException` → HTTP 400 (`CurrencyExceptionHandler`).

## Umrechnung an den Schnittstellen

- **Ort:** nur in Controllern/DTO-Mapping über `CurrencyConversionService`. `logic`, `data` und `entities` kennen nur `Money` (ArchUnit-Test `MoneyArchitectureTest`).
- **API-Format:** `MoneyDto { amount, currency, exchangeRate?, exchangeRateDate? }`. `amount` und `exchangeRate` sind Dezimal-Strings (`type: string, format: decimal` in OpenAPI), nie JSON-Zahlen. `exchangeRate`/`exchangeRateDate` nur bei umgerechneten Werten, für „Kurs vom …“ im Frontend.
- **Eingehend:** `Money fromDto(MoneyDto)` bzw. `toBase(CurrencyAmount)`: `base = amount / rate`, Skala 8, `HALF_EVEN`.
- **Ausgehend:** `toDto(money, currency, rounding)`: `amount = base × rate`. Ziel-Währung über `responseCurrency(requested, preferred)`: Query-Parameter `currency`, sonst `AppUser.preferredCurrency`, sonst USD.
- **Rundung ausgehend (`MoneyRounding`):**
  - `PRICE`: Preise mit kleinen Beträgen: 6 Nachkommastellen, `HALF_EVEN`.
  - `AMOUNT`: Kontostände, Rechnungs- und Zahlbeträge: Nachkommastellen der Währung (2 für EUR, 0 für JPY), `HALF_EVEN`.
  - `PAYOUT`: Auszahlungen: wie `AMOUNT`, aber `DOWN`, damit nie mehr ausgezahlt wird als das Guthaben deckt.
  - Formatierung (Tausendertrenner, Symbol) macht das Frontend.
- **Zeitbezug des Kurses:** aktuelle Werte mit dem neuesten Kurs, historische Werte mit `toDto(..., at)` zum Kurs am Ereignisdatum, Buchungen mit dem gespeicherten Kurs.
- **Roundtrip:** Hin- und Rückrechnung kann minimal abweichen. Wo das stört, die Originaleingabe zusätzlich zur Anzeige speichern.
- **Beispiel:** `GET /api/v1/accounting/balances?currency=EUR` liefert Guthaben und Einnahmen in EUR. `PUT /api/v1/users/me/preferred-currency` mit `{"currency": "EUR"}` (oder `null` für USD) setzt die Standardwährung.

## Tests

- `MoneyTest` (Skala, Rundung, Arithmetik), `CurrencyConversionServiceTest` (Umrechnung, Rundungsregeln, veraltete Kurse), `EcbExchangeRateProviderTest` (Kreuzkurse)
- `MoneyPersistenceTest` (alle Geldspalten inkl. `_aud` sind `NUMERIC(19, 8)`), `AccountingFlowTest` (API-Umrechnung der Kontostände), `MoneyArchitectureTest`

## Offen

- Wechselkursaufschlag/Spread bei Ein- und Auszahlungen.
