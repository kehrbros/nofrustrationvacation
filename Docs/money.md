# Geldbeträge und Währungen (Konzept)

Status: Konzept, noch nicht umgesetzt. Betrifft die `// TODO PKE change to monetary value`-Felder in `market.entities` und `AppUser.accountBalance`.

## Grundsatz

- **Eine Basiswährung im Backend:** Logik, Persistenz, Matching und Abrechnung rechnen ausschließlich in der Basiswährung. Fremdwährungen existieren nur in `api`/`dto` und in der Kursverwaltung.
- **Basiswährung: USD (Vorschlag).** LLM-Anbieter bepreisen und rechnen in USD ab, damit entfällt die Umrechnung auf der Anbieterseite. Alternative EUR, falls Zahlungsanbieter/Buchhaltung EUR erzwingen. Festgelegt als Konstante `Money.BASE_CURRENCY`, nicht konfigurierbar: ein späterer Wechsel erfordert eine Datenmigration.
- **Warum nur eine Währung:** Vergleiche (`minCost ≤ price ≤ maxCost`) und Summen sind ohne Kurs eindeutig, Envers-Historie bleibt kursunabhängig, kein Mischen von Währungen in einer Rechnung.

## Typ `Money` (`com.vacantagent.common.money`)

- `record Money(BigDecimal amount)`, immer in Basiswährung, daher ohne Währungsfeld. Ersetzt rohe `BigDecimal` in Entities, Logik und Repositories.
- **Skala:** fest 8 Nachkommastellen (`Money.SCALE = 8`), im Konstruktor per `setScale(8, HALF_EVEN)` normiert. Grund: Kosten einzelner Requests liegen im Bereich von Bruchteilen eines Cents (z. B. 1.000 Tokens × 0,10 USD/Mio. = 0,0001 USD).
- **Operationen:** `plus`, `minus`, `times(BigDecimal)`, `dividedBy(BigDecimal)` (intern `MathContext.DECIMAL128`, danach Skala 8), `isNegative`, `compareTo`, `Money.ZERO`, `Money.of("1.25")`. Kein `double`, kein `equals` auf ungleich skalierten `BigDecimal`.
- **Preis pro Mio. Tokens:** bleibt `Money` (Feldname trägt die Einheit, wie heute). Kostenberechnung zentral: `Money costFor(long tokens)` = `price.times(tokens).dividedBy(1_000_000)`.
- **Fremdwährungsbetrag:** `record CurrencyAmount(BigDecimal amount, java.util.Currency currency)`, nur in `api`/`dto` und im Umrechnungsservice. ArchUnit-Regel: `CurrencyAmount` und `MoneyDto` nicht in `logic`, `data`, `entities`.

## Persistenz

- **Mapping:** `MoneyConverter implements AttributeConverter<Money, BigDecimal>` mit `@Converter(autoApply = true)`, analog zu `DurationConverter`. Eine Spalte pro Betrag, keine Währungsspalte.
- **Spaltentyp:** `NUMERIC(19, 8)` überall (max. ~10^11 Einheiten Basiswährung). In der Entity `@Column(precision = 19, scale = 8)`.
- **Betroffene Felder:**
  - `Agreement.agreedPricePerMillionInputTokens`, `agreedPricePerMillionOutputTokens` (auch in `agreement_aud`; Envers übernimmt den Converter automatisch)
  - `ProviderOffer.pricePerMillionInputTokens`, `pricePerMillionOutputTokens`
  - `ConsumerOrder.minCostPerMillionTokens`, `maxCostPerMillionTokens`
  - `AppUser.accountBalance`
- **Ist-Abweichung:** Entities deklarieren `scale = 6`, `V1__initial_schema.sql` aber `NUMERIC(38, 2)`. Bei 2 Nachkommastellen gehen Token-Preise wie 0,075 USD/Mio. verloren. Neue Migration (`V3__money_columns.sql`, V2 ist durch Auth belegt) stellt alle oben genannten Spalten inkl. `_aud` auf `NUMERIC(19, 8)` um.
- **Umrechnungen mit Buchungswirkung** (Einzahlung, Auszahlung) werden mit Originalbetrag, Originalwährung, Kurs, Kursdatum und Basisbetrag gespeichert (künftige Buchungs-/Transaktionstabelle), damit jede Buchung später nachvollziehbar ist.

## Wechselkurse

- **Quelle:** EZB-Referenzkurse (kostenlos, ohne API-Key, ca. 30 Währungen, werktags ~16:00 MEZ, Basis EUR). USD-Kreuzkurse werden daraus berechnet (`EUR→X / EUR→USD`). Falls stündliche Kurse nötig werden: Wechsel auf kostenpflichtigen Anbieter (z. B. Open Exchange Rates) hinter demselben Interface `ExchangeRateProvider`.
- **Speicherung:** Tabelle `exchange_rate(currency CHAR(3), rate NUMERIC(19, 10), rate_date DATE, fetched_at TIMESTAMP WITH TIME ZONE, source VARCHAR)`, eindeutig auf `(currency, rate_date)`. `rate` = Einheiten Fremdwährung je 1 Basiswährung. Nicht auditiert, die Tabelle ist selbst die Historie.
- **Abruf:** `@Scheduled`-Job täglich (z. B. 17:00 Europe/Berlin) plus beim Start, falls der heutige Kurs fehlt. In-Memory-Cache für den jeweils neuesten Kurs.
- **Veraltete Kurse:** Wochenende/Feiertage nutzen den letzten verfügbaren Kurs. Ist dieser älter als 4 Tage: Anzeige weiterhin erlaubt, buchungswirksame Umrechnung wird mit `BusinessException` abgelehnt.
- **Unterstützte Währungen:** Whitelist in `application.properties` (`vacantagent.money.currencies=USD,EUR,GBP,CHF`). Nicht unterstützte Währung → 400.

## Umrechnung an den Schnittstellen

- **Ort:** ausschließlich im Controller-/DTO-Mapping über `CurrencyConversionService`. `logic` bekommt und liefert nur `Money`.
- **API-Format:** `MoneyDto { amount: string, currency: string }`, `amount` als Dezimal-String (keine JSON-Floats, Frontend rechnet nicht mit `number`). In OpenAPI als `type: string, format: decimal` (`@Schema`), damit der generierte Frontend-Client korrekt typisiert.
- **Eingehend (Request):** Client sendet Betrag + Währung. Backend rechnet mit dem aktuellen Kurs in Basiswährung um: `base = amount / rate`, Skala 8, `HALF_EVEN`. Ist `currency` bereits Basiswährung, keine Umrechnung.
- **Ausgehend (Response):** Ziel-Währung = Query-Parameter `currency`, sonst `AppUser.preferredCurrency` (neues Feld), sonst Basiswährung. `amount = base × rate`.
- **Rundung ausgehend:**
  - Preise (pro Mio. Tokens) und Kosten einzelner Requests: 6 Nachkommastellen, `HALF_EVEN`, damit kleine Preise sichtbar bleiben.
  - Kontostände, Rechnungs- und Zahlbeträge: Nachkommastellen der Währung (`Currency.getDefaultFractionDigits()`, z. B. 2 für EUR, 0 für JPY), `HALF_EVEN`.
  - Auszahlungen: `DOWN`, damit nie mehr ausgezahlt wird als das Guthaben deckt.
  - Formatierung (Tausendertrenner, Symbol) macht das Frontend.
- **Zeitbezug des Kurses:**
  - Aktuelle Werte (Angebote, Kontostand): neuester Kurs.
  - Historische Werte (z. B. Kosten einer vergangenen Abrechnung): Kurs zum Ereigniszeitpunkt (`rate_date ≤ Ereignisdatum`, neuester).
  - Buchungen: der beim Buchen verwendete Kurs wird mitgespeichert und nie neu berechnet.
- **Transparenz:** umgerechnete Responses enthalten `exchangeRate` und `exchangeRateDate` (optionale Felder im `MoneyDto` oder einmal pro Response), damit das Frontend „Kurs vom …“ anzeigen kann.
- **Roundtrip:** Werte, die der Nutzer in Fremdwährung eingegeben hat, können nach Hin- und Rückrechnung minimal abweichen. Wo das stört (z. B. Max-Preis einer Order), Originaleingabe zusätzlich nur zur Anzeige speichern.

## Tests

- Unit-Tests für `Money` (Skala, Rundung, Arithmetik) und `CurrencyConversionService` (Kreuzkurse, Rundungsregeln, veraltete Kurse).
- ArchUnit-Regeln: keine `BigDecimal`-Geldfelder in Entities, keine Fremdwährungstypen außerhalb `api`/`dto`.
- Migrationstest: Spalten inkl. `_aud` haben `NUMERIC(19, 8)`.

## Offene Entscheidungen

- Basiswährung USD oder EUR.
- Wechselkursaufschlag/Spread bei Ein- und Auszahlungen (hier nicht vorgesehen).
- Ob Abrechnung pro Request in voller Präzision oder gesammelt pro Zeitraum gerundet gebucht wird.
