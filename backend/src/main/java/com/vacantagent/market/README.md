# Market

Pairs consumer orders with provider offers 1:1 and persists each pairing as an `Agreement`.

## Architecture

```
caller ──► BrokerLogic ──► MatchingEngine (1 thread) ──► AgreementWriter (1 thread) ──► agreement table
              │                 │                              │
              │ entity → Book*  │ LimitStrategy + OrderBook    │ AgreementRepository.saveAll
              │                 │ MarketRules                  │ (Envers audits)
              ◄──────── future completes once the agreement is saved ◄──┘
```

- **`logic/BrokerLogic`**: entry point. Takes an `OrderDto`/`OfferDto`, maps it with `mapper/OrderMapper`/`OfferMapper` to a `ConsumerOrder`/`ProviderOffer` (a new offer starts with all its tokens available) and saves it, then submits it as a `BookOrder`/`BookOffer`. Returns a `Submission`: the new entity's id plus a `CompletableFuture<MatchResult>`.
- **`engine/MatchingEngine`**: a single thread owns the book. Commands are queued and run in arrival order, so no locks are needed.
- **`matching/LimitStrategy`**: decides what happens to an entry: match, rest or reject. Builds the `Agreement`.
- **`matching/OrderBook`**: waiting offers and orders, each kept in a `QuadrantIndex` (2D segment tree).
- **`logic/MarketRules`**: plain boolean business checks, run inside the matching loop. Holds the `Clock` for expiry checks.
- **`data/AgreementWriter`**: saves agreements in batches on its own thread, retries 3 times and completes each agreement's future.
- **`logic/AgreementReaper`**: scheduled. Closes agreements the consumer leaves unused and sends their offers back into the market.
- **`logic/BookExpirySweeper`**: scheduled. Removes expired offers and orders from the book.
- **`config/MarketConfig`**: the only Spring wiring. Settings are under `vacantagent.market.*` (`MarketProperties`).

## What defines a match

- **Prices**: entity `Money` values (base currency, see Docs/money.md) are converted to `long` micro-units (`Prices`).
  - Incoming `MoneyDto` prices are converted to the base currency in `OfferMapper`/`OrderMapper`.
  - Order limits are rounded down, offer prices are rounded up, so rounding never creates a pair that doesn't fit.
- **Entry checks** (`MarketRules.isValid`): every price must be `> 0` and `<= vacantagent.market.max-price`. Otherwise the entry is `Rejected`. Null prices are rejected too.
  - **Provider availability** (`isProviderOfferAvailable`): an offer whose provider has ever failed (`lastErrorAt` set) is rejected. There is no backoff: once failed, the offer is out.
  - **Expired offer**: an offer past its `offeredUntil` is rejected.
- **Expiry**:
  - An offer expires at `offeredUntil`, an order at `createdAt + marketWaitTime`. If the value (or either part) is null, the entry never expires. `BookOffer`/`BookOrder` precompute it as epoch millis (`expiresAt`).
  - Matching skips expired waiting entries and tries the next-best candidate.
  - An order whose wait time is already over (e.g. zero) still gets one chance to match on arrival. If nothing fits, it is `Rejected` instead of resting.
  - `BookExpirySweeper` submits `MatchingEngine.ExpireEntries` every `expiry-sweep-interval` (default 10s), which removes expired entries from the book (result `Expired`). `OrderBook` keeps a queue sorted by expiry, so this doesn't scan the book. Matching already skips expired entries, so the sweep only frees memory.
- **Fit**: an order and an offer fit when `priceInput <= maxInput` **and** `priceOutput <= maxOutput`.
- **Business veto** (`MarketRules.canMatch`): can block a fitting pair, never loosen the fit. The next-best candidate is then tried.
  - **Model**: an order may leave `modelName`, or both `modelProvider` and `modelName`, empty to accept any value. A value that is given must equal the offer's exactly (case-sensitive). Rejecting a model without a provider and blank values is up to the controller.
- **Priority**:
  - A new order gets the **cheapest** fitting offer (lowest input + output total).
  - A new offer gets the **highest-paying** fitting order (highest max total).
  - On equal totals, the **oldest** entry wins.
- **No fit**: the entry is `Resting` in the book until a counterpart arrives, it expires, or it is cancelled.
- **Duplicate id** of a waiting entry: `Rejected`.

## Agreement

- `consumerOrder`, `providerOffer`: the matched entities.
- Agreed prices: the offer's exact `Money` prices. The consumer pays the provider's price.
- `agreedAt`: time of the match. `validUntil`: the offer's `offeredUntil`.
- The `Matched` future completes after the save, so the agreement has its id.
- If saving fails, the future fails. The pair has already left the book, and the agreement is logged so it can be replayed.

### Unused agreements

- `lastSuccessAt`: time of the last call through the agreement. Not audited, as it changes on every call. The LLM proxy records it with `IBrokerLogic.recordAgreementSuccess(agreementId)`. A `false` result means the agreement is closed, and the call must be refused.
- `AgreementReaper` runs every `agreement-sweep-interval` (default 5s). An open agreement (still before `validUntil`) without a call for `agreement-idle-timeout` (default 30s) is closed. "Last call" is `lastSuccessAt`, or `agreedAt` if it was never used.
  - Closing locks the row and checks again, sets `lastErrorAt` and commits. Only then is the offer resubmitted, where it passes the entry checks again (an expired offer is rejected).
  - `recordAgreementSuccess` only updates open agreements, so an agreement can't be used and closed at the same moment.
  - The consumer order does not come back: it stays tied to the closed agreement.

## Limits

- The book lives in memory only. On startup, `MarketConfig` reloads every offer with `availableTokens > 0` and no open agreement (a closed agreement, `lastErrorAt` set, releases its offer), oldest first.
  - TODO: an agreement may not use all of an offer's tokens. Then the offer should re-enter the market with the rest, which the reload query and the 1:1 pairing don't support yet.
  - Offers that have expired or fail the entry checks (provider failed, invalid prices) are skipped.
  - Orders re-enter if they have no agreement yet. Orders whose wait time is over or with invalid prices are skipped.
  - If the app stops between closing an agreement and resubmitting its offer, the offer comes back on this reload.
  - Cancelled offers and orders come back after a restart, because a cancel is not persisted.
- The book holds entity snapshots: a provider error recorded after an offer entered the book is not seen. Cancel the offer (`BrokerLogic.cancelOffer`) to take it out.
- Run on a single instance, because each instance would have its own book.
- The expiry queue drops matched or cancelled entries only when their expiry comes up, so an offer whose `offeredUntil` is far in the future keeps a small record in memory until then.