# Money amounts and currencies

- **Principle:** logic, persistence and accounting calculate exclusively in the base currency. Foreign currencies exist only at the API (`api`/`dto`) and in the module `com.nfv.currency`.
- **Base currency: USD**, fixed in `Money.BASE_CURRENCY`. Not configurable: a change requires a data migration.
- **Why only one currency:** comparisons and sums are unambiguous without a rate, the Envers history stays independent of rates.

## Type `Money` (`com.nfv.common.money`)

- `record Money(BigDecimal amount)`, always base currency, hence without a currency field. For all money amounts in entities, logic and repositories, never raw `BigDecimal`.
- **Scale:** fixed 8 decimal places (`Money.SCALE`), normalised in the constructor with `HALF_EVEN`, so `equals` is safe.
- **Operations:** `plus`, `minus`, `times`, `dividedBy` (internally `MathContext.DECIMAL128`), `isNegative`, `isZero`, `isGreaterThan`, `isLessThan`, `compareTo`, `Money.ZERO`, `Money.of(...)`. Never `double`.
- **Foreign currency amount:** `record CurrencyAmount(BigDecimal amount, Currency currency)`, only at the API and in the module `currency`.

## Exception: prices of bookings

- `Booking.price` + `currency` (module `bookings`) is the price as stated in the confirmation (e.g. 412.50 EUR), in any currency, `NUMERIC(12, 2)`. It is only information for the user, the app neither calculates with it nor converts it. A conversion to the base currency would distort the booked amount.
- Likewise `BookingReservation` (table `booking_reservation`, booking of an alternative via LiteAPI): net price, commission and final price in the currency of the offer, passed to PayPal as is, no conversion, see [accommodation-alternatives.md](accommodation-alternatives.md#payment). The only calculation is net price + commission (`Pricing`).
- Likewise the offer of an agent proposal (`advisor_proposal.stay_price`, `ProposalStay`): the price the search returned, in the currency of the offer, re-checked when booking, see [advisor.md](advisor.md#search_accommodations).
- Likewise plans (`V50`): offers of the steps (`advisor_proposal_step.stay_price`), extra cost of the plan (`advisor_proposal.extra_cost`) and the combined payment (`PlanPayment.total`, table `plan_payment`, sum of the reservations in their single currency), see [incident-plans.md](incident-plans.md).
- `MoneyArchitectureTest` and `MoneyPersistenceTest` explicitly exclude `Booking.price`, `BookingReservation`, `PlanPayment` and the tables `booking`, `booking_aud`, `booking_reservation`, `advisor_proposal`, `advisor_proposal_step`, `plan_payment` (`ProposalStay` is not an `@Entity`, so it is not covered by `MoneyArchitectureTest`).

## Persistence

- **Mapping:** `common.data.MoneyConverter` (`@Converter(autoApply = true)`, analogous to `DurationConverter`). One column per amount, no currency column. Entity field: `@Column(precision = 19, scale = Money.SCALE) private Money ...;`
- **Column type:** `NUMERIC(19, 8)`. Create new money columns in migrations the same way, in `_aud` tables as well.
- **Converted by `V4__money.sql`** (previously entities `scale = 6`, schema `NUMERIC(38, 2)`):
  - prices of market, offers and orders (removed with `V18__drop_market_and_llm.sql`)
  - `AppUser.accountBalance` (replaced by `accounting_account.balance` since `V11__accounting.sql`), additionally new field `AppUser.preferredCurrency`
- **Bookings with conversion** (top-up/payout, see [accounting.md](accounting.md)): `CurrencyConversionService.toBaseForBooking(...)` returns a `CurrencyConversion` (original amount, base amount, rate, rate date). In the API it is mapped to `common.money.OriginalAmount` (`@Embeddable`, columns `original_amount`, `original_currency`, `exchange_rate`, `exchange_rate_date`) and stored with the booking, never recalculated.

## Exchange rates (`com.nfv.currency`)

- **Source:** `EcbExchangeRateProvider`, ECB reference rates (no API key, weekdays ~16:00 CET, base EUR). Rates to the base currency as cross rate `EUR→X / EUR→USD`. Another source: another implementation of `ExchangeRateProvider`.
- **Storage:** table `exchange_rate(currency, rate NUMERIC(19, 10), rate_date, fetched_at, source)`, unique on `(currency, rate_date)`. `rate` = units of foreign currency per 1 USD. Not audited, the table itself is the history.
- **Fetching:** `ExchangeRateUpdater` at startup and via cron (`nfv.money.exchange-rates.update-cron`, Mon–Fri 17:00 Europe/Berlin). Errors are only logged, the last stored rate then applies. Can be disabled with `nfv.money.exchange-rates.update-enabled=false` (set in tests via `src/test/resources/config/application.properties`).
- **Cache:** latest rate per currency in `CurrencyConversionService`, cleared after every fetch.
- **Outdated rates:** display always uses the last rate (weekend/holidays). Conversion that affects bookings (`toBaseForBooking`) rejects rates older than `nfv.money.exchange-rates.max-age-days` (4) with `ExchangeRateUnavailableException`.
- **Supported currencies:** `nfv.money.currencies=USD,EUR,GBP,CHF`. Other currency → `UnsupportedCurrencyException` → HTTP 400 (`CurrencyExceptionHandler`).

## Conversion at the interfaces

- **Location:** only in controllers/DTO mapping via `CurrencyConversionService`. `logic`, `data` and `entities` only know `Money` (ArchUnit test `MoneyArchitectureTest`).
- **API format:** `MoneyDto { amount, currency, exchangeRate?, exchangeRateDate? }`. `amount` and `exchangeRate` are decimal strings (`type: string, format: decimal` in OpenAPI), never JSON numbers. `exchangeRate`/`exchangeRateDate` only for converted values, for "rate as of …" in the frontend.
- **Incoming:** `Money fromDto(MoneyDto)` or `toBase(CurrencyAmount)`: `base = amount / rate`, scale 8, `HALF_EVEN`.
- **Outgoing:** `toDto(money, currency, rounding)`: `amount = base × rate`. Target currency via `responseCurrency(requested, preferred)`: query parameter `currency`, otherwise `AppUser.preferredCurrency`, otherwise USD.
- **Outgoing rounding (`MoneyRounding`):**
  - `PRICE`: prices with small amounts: 6 decimal places, `HALF_EVEN`.
  - `AMOUNT`: balances, invoice and payment amounts: decimal places of the currency (2 for EUR, 0 for JPY), `HALF_EVEN`.
  - `PAYOUT`: payouts: like `AMOUNT`, but `DOWN`, so that never more is paid out than the balance covers.
  - Formatting (thousands separator, symbol) is done by the frontend.
- **Point in time of the rate:** current values with the latest rate, historical values with `toDto(..., at)` at the rate of the event date, bookings with the stored rate.
- **Round trip:** converting there and back may deviate minimally. Where this matters, store the original input in addition to the display value.
- **Example:** `GET /api/v1/accounting/balances?currency=EUR` returns credit and earnings in EUR. `PUT /api/v1/users/me/preferred-currency` with `{"currency": "EUR"}` (or `null` for USD) sets the default currency.

## Tests

- `MoneyTest` (scale, rounding, arithmetic), `CurrencyConversionServiceTest` (conversion, rounding rules, outdated rates), `EcbExchangeRateProviderTest` (cross rates)
- `MoneyPersistenceTest` (all money columns incl. `_aud` are `NUMERIC(19, 8)`), `AccountingFlowTest` (API conversion of balances), `MoneyArchitectureTest`

## Open

- Exchange rate markup/spread for top-ups and payouts.
