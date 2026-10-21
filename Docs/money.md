# Geldbeträge und Währungen

- **Grundsatz:** Logik, Persistenz, Matching und Abrechnung rechnen ausschließlich in der Basiswährung. Fremdwährungen existieren nur an der API (`api`/`dto`) und im Modul `com.vacantagent.currency`.
- **Basiswährung: USD**, fest in `Money.BASE_CURRENCY`. LLM-Anbieter bepreisen in USD, damit entfällt die Umrechnung auf der Anbieterseite. Nicht konfigurierbar: ein Wechsel erfordert eine Datenmigration.
- **Warum nur eine Währung:** Vergleiche (`minCost ≤ price ≤ maxCost`) und Summen sind ohne Kurs eindeutig, die Envers-Historie bleibt kursunabhängig.

## Typ `Money` (`com.vacantagent.common.money`)

- `record Money(BigDecimal amount)`, immer Basiswährung, daher ohne Währungsfeld. Für alle Geldbeträge in Entities, Logik und Repositories, nie rohe `BigDecimal`.
- **Skala:** fest 8 Nachkommastellen (`Money.SCALE`), im Konstruktor per `HALF_EVEN` normiert, daher ist `equals` sicher. Grund: Kosten einzelner Requests liegen bei Bruchteilen eines Cents (1.000 Tokens × 0,10 USD/Mio. = 0,0001 USD).
- **Operationen:** `plus`, `minus`, `times`, `dividedBy` (intern `MathContext.DECIMAL128`), `isNegative`, `isZero`, `isGreaterThan`, `isLessThan`, `compareTo`, `Money.ZERO`, `Money.of(...)`. Nie `double`.
- **Preise pro Mio. Tokens** sind `Money`, der Feldname trägt die Einheit. Kosten: `price.costFor(tokens)`.
- **Fremdwährungsbetrag:** `record CurrencyAmount(BigDecimal amount, Currency currency)`, nur an der API und im Modul `currency`.

## Persistenz

- **Mapping:** `common.data.MoneyConverter` (`@Converter(autoApply = true)`, analog `DurationConverter`). Eine Spalte pro Betrag, keine Währungsspalte. Entity-Feld: `@Column(precision = 19, scale = Money.SCALE) private Money ...;`
- **Spaltentyp:** `NUMERIC(19, 8)`. Neue Geldspalten in Migrationen ebenso anlegen, in `_aud`-Tabellen auch.
- **Umgestellt durch `V4__money.sql`** (vorher Entities `scale = 6`, Schema `NUMERIC(38, 2)`):
  - `Agreement.agreedPricePerMillionInputTokens`, `agreedPricePerMillionOutputTokens` inkl. `agreement_aud`
  - `ProviderOffer.pricePerMillionInputTokens`, `pricePerMillionOutputTokens`
  - `ConsumerOrder.minCostPerMillionTokens`, `maxCostPerMillionTokens`; durch `V5__align_order_offer_columns.sql` ersetzt durch `maxCostPerMillionInputTokens`, `maxCostPerMillionOutputTokens` (ebenfalls `NUMERIC(19, 8)`)
  - `AppUser.accountBalance`, zusätzlich neues Feld `AppUser.preferredCurrency`
- **Buchungen mit Umrechnung** (Ein-/Auszahlung, noch nicht umgesetzt): `CurrencyConversionService.toBaseForBooking(...)` liefert eine `CurrencyConversion` (Originalbetrag, Basisbetrag, Kurs, Kursdatum). Alle Felder mit der Buchung speichern, nie neu berechnen.

## Wechselkurse (`com.vacantagent.currency`)

- **Quelle:** `EcbExchangeRateProvider`, EZB-Referenzkurse (ohne API-Key, werktags ~16:00 MEZ, Basis EUR). Kurse zur Basiswährung als Kreuzkurs `EUR→X / EUR→USD`. Andere Quelle: weitere Implementierung von `ExchangeRateProvider`.
- **Speicherung:** Tabelle `exchange_rate(currency, rate NUMERIC(19, 10), rate_date, fetched_at, source)`, eindeutig auf `(currency, rate_date)`. `rate` = Einheiten Fremdwährung je 1 USD. Nicht auditiert, die Tabelle ist selbst die Historie.
- **Abruf:** `ExchangeRateUpdater` beim Start und per Cron (`vacantagent.money.exchange-rates.update-cron`, Mo–Fr 17:00 Europe/Berlin). Fehler werden nur geloggt, es gilt dann der letzte gespeicherte Kurs. Abschaltbar mit `vacantagent.money.exchange-rates.update-enabled=false` (in Tests gesetzt über `src/test/resources/config/application.properties`).
- **Cache:** neuester Kurs je Währung im `CurrencyConversionService`, nach jedem Abruf geleert.
- **Veraltete Kurse:** Anzeige nutzt immer den letzten Kurs (Wochenende/Feiertage). Buchungswirksame Umrechnung (`toBaseForBooking`) lehnt Kurse älter als `vacantagent.money.exchange-rates.max-age-days` (4) mit `ExchangeRateUnavailableException` ab.
- **Unterstützte Währungen:** `vacantagent.money.currencies=USD,EUR,GBP,CHF`. Andere Währung → `UnsupportedCurrencyException` → HTTP 400 (`CurrencyExceptionHandler`).

## Umrechnung an den Schnittstellen

- **Ort:** nur in Controllern/DTO-Mapping über `CurrencyConversionService`. `logic`, `data` und `entities` kennen nur `Money` (ArchUnit-Test `MoneyArchitectureTest`).
- **API-Format:** `MoneyDto { amount, currency, exchangeRate?, exchangeRateDate? }`. `amount` und `exchangeRate` sind Dezimal-Strings (`type: string, format: decimal` in OpenAPI), nie JSON-Zahlen. `exchangeRate`/`exchangeRateDate` nur bei umgerechneten Werten, für „Kurs vom …“ im Frontend.
- **Eingehend:** `Money fromDto(MoneyDto)` bzw. `toBase(CurrencyAmount)`: `base = amount / rate`, Skala 8, `HALF_EVEN`.
- **Ausgehend:** `toDto(money, currency, rounding)`: `amount = base × rate`. Ziel-Währung über `responseCurrency(requested, preferred)`: Query-Parameter `currency`, sonst `AppUser.preferredCurrency`, sonst USD.
- **Rundung ausgehend (`MoneyRounding`):**
  - `PRICE`: Preise pro Mio. Tokens, Kosten einzelner Requests: 6 Nachkommastellen, `HALF_EVEN`.
  - `AMOUNT`: Kontostände, Rechnungs- und Zahlbeträge: Nachkommastellen der Währung (2 für EUR, 0 für JPY), `HALF_EVEN`.
  - `PAYOUT`: Auszahlungen: wie `AMOUNT`, aber `DOWN`, damit nie mehr ausgezahlt wird als das Guthaben deckt.
  - Formatierung (Tausendertrenner, Symbol) macht das Frontend.
- **Zeitbezug des Kurses:** aktuelle Werte mit dem neuesten Kurs, historische Werte mit `toDto(..., at)` zum Kurs am Ereignisdatum, Buchungen mit dem gespeicherten Kurs.
- **Roundtrip:** Hin- und Rückrechnung kann minimal abweichen. Wo das stört (z. B. Max-Preis einer Order), die Originaleingabe zusätzlich zur Anzeige speichern.
- **Beispiel:** `GET /api/v1/users/me?currency=EUR` liefert `accountBalance` in EUR. `PUT /api/v1/users/me/preferred-currency` mit `{"currency": "EUR"}` (oder `null` für USD) setzt die Standardwährung.

## Tests

- `MoneyTest` (Skala, Rundung, Arithmetik), `CurrencyConversionServiceTest` (Umrechnung, Rundungsregeln, veraltete Kurse), `EcbExchangeRateProviderTest` (Kreuzkurse)
- `MoneyPersistenceTest` (alle Geldspalten inkl. `_aud` sind `NUMERIC(19, 8)`), `UserControllerCurrencyTest` (API-Umrechnung), `MoneyArchitectureTest`

## Offen

- Wechselkursaufschlag/Spread bei Ein- und Auszahlungen.
- Buchungstabelle für Ein-/Auszahlungen; Abrechnung pro Request in voller Präzision oder gesammelt pro Zeitraum.
