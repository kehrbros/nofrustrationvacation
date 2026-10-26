# Market

Pairs consumer orders with provider offers 1:1 and persists each pairing as an `Agreement`.

## Architecture

```
caller ──► MarketLogic ──── command ────► MatchingEngine (1 thread)
           (coordinates)  ◄── MatchResult ── LimitStrategy + OrderBook + MarketRules
              │
              │ Matched → agreement
              ▼
           AgreementWriter (1 thread) ──► agreement table (AgreementRepository.saveAll, Envers audits)
              │
              ◄──── caller's future completes once the agreement is saved
```

`MatchingEngine` is solely responsible for matching and does no I/O. Everything around it is coordinated by
`MarketLogic`, the only caller of the engine: persisting entries, persisting agreements, restoring the book at
startup and starting/stopping the engine. `AgreementReaper` and `BookExpirySweeper` also go through `MarketLogic`.

- **`api/MarketTools`**: MCP tools at `/mcp` (Spring AI `@McpTool`, sync server, streamable HTTP, JWT required). `place_order` waits via `acceptOrderAndAwaitMatch` until `MATCHED`, `REJECTED` (with reason, also when `marketWaitTime` is over) or `CANCELLED`; `marketWaitTime` is required and at most `PT60S`, below common MCP client timeouts. `place_offer` returns `MATCHED` or `RESTING` right away, or `REJECTED`. `cancel_order`, `cancel_offer` fail if the entry is not waiting.
  - Prices are flat decimal strings plus one `currency` parameter, which is easier for agents than nested `MoneyDto`s.
  - Orders and offers belong to the authenticated user (`appUser`, from `CurrentUser` in the `SecurityContext`; the sync server runs tools on the request thread). Only the owner may cancel; anyone else gets "not waiting", so ids of other users stay hidden.
  - `MarketRequestPreparer` trims model values, turns empty ones into `null` and refuses an order with `modelName` but no `modelProvider`. An offer gives either `agentId` alone or all of `modelProvider`, `modelName` and `secret`; anything else is refused, as is an `agentId` of another user's or a missing agent (`MarketTools`, "Agent n not found"). Refusals (`InvalidMarketRequestException`) become MCP tool errors.
  - Agreed prices in results are in the user's preferred currency (`SubmissionDtoMapper`).
- **`logic/MarketLogic`**: entry point. Takes an `OrderDto`/`OfferDto`, maps it with `mapper/OrderMapper`/`OfferMapper` to a `ConsumerOrder`/`ProviderOffer` (a new offer starts with all its tokens available; its `Agent` holds `modelProvider`, `modelName` and the `secret` for calling it and is either the existing one of `agentId` or a new one owned by the user; the secret never leaves the backend) and saves it in one transaction, then submits it as a `BookOrder`/`BookOffer`. Returns a `Submission`: the new entity's id plus a `CompletableFuture<MatchResult>`.
  - Coordinates the engine: hands each `Matched` agreement to the `AgreementWriter`, and the result completes only once it is saved. The hand-off is attached before the command is queued, so it runs on the engine thread before the next command.
  - `acceptOrderAndAwaitMatch`: like `acceptOrder`, but the result completes only when the order's wait ends: `Matched` once saved (on arrival or later), `Rejected` if refused or its wait time is over (`createdAt + marketWaitTime`, then cancelled through the engine), `Cancelled` if cancelled. Fails if saving fails or the market stops. The waiter is registered before the order is submitted, so no match is missed. Used by callers that must wait for an agreement, e.g. the LLM proxy.
  - Lifecycle: on startup (`@PostConstruct`) restores waiting offers and orders into the book, then starts the engine; on shutdown (`@PreDestroy`) stops it before the writer flushes.
- **`engine/MatchingEngine`**: matching only. A single thread owns the book. Commands are queued and run in arrival order, so no locks are needed. Its `Matched` results carry agreements that are not saved yet.
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
- **One waiting order per user and model**: an order is `Rejected` while the same user has another order waiting for the same `modelProvider` and `modelName` (`null` counts as a value, so one "any" order at a time). `OrderBook` indexes waiting orders by `BookOrder.ownerModelKey()`. An expired waiting order does not block and is removed on the spot. Orders without owner are exempt.

## Agreement

- `consumerOrder`, `providerOffer`: the matched entities.
- Agreed prices: the offer's exact `Money` prices. The consumer pays the provider's price.
- `agreedAt`: time of the match. `validUntil`: the offer's `offeredUntil`.
- The `Matched` future completes after the save, so the agreement has its id.
- If saving fails, the future fails. The pair has already left the book, and the agreement is logged so it can be replayed.

### Unused agreements

- `ConsumerOrder.lastSuccessAt`: time of the last call through the agreement. The LLM proxy records it with `IMarketLogic.recordAgreementSuccess(agreementId)`.
- **Idle** (`Agreement.isIdleSince`): no call for `agreement-idle-timeout` (default 30s). "Last call" is `lastSuccessAt`, or `agreedAt` if it was never used. Nothing is stored: being idle follows from time alone, there is no closed flag.
  - An idle agreement refuses calls: `recordAgreementSuccess` returns `false`, and the call must be refused. So `lastSuccessAt` never changes once idle, and every agreement becomes idle at one fixed moment.
  - Its offer is free again and goes back into the market.
- **Releasing** (`AgreementReaper`, every `agreement-sweep-interval`, default 5s): each sweep resubmits the offers of still valid agreements that became idle since the previous sweep. The windows join without gaps or overlaps (the end of the last window is kept in memory), so each offer is released exactly once.
  - The first window starts at `IMarketLogic.restoredAt()`. Offers of agreements idle before that came back with the restore, also those that became idle while the app was down.
  - The resubmitted offer passes the entry checks again (an expired offer is rejected).
  - Recording a call and releasing both lock the consumer order row and re-check, so an agreement can't be used and released at the same moment.
  - The consumer order does not come back: it stays tied to its agreement.

## Limits

- The book lives in memory only. On startup, `MarketLogic` reloads every offer with `availableTokens > 0` and no active agreement (still valid and not idle at `restoredAt`), oldest first.
  - TODO: an agreement may not use all of an offer's tokens. Then the offer should re-enter the market with the rest, which the reload query and the 1:1 pairing don't support yet.
  - Offers that have expired or fail the entry checks (provider failed, invalid prices) are skipped.
  - Orders re-enter if they have no agreement yet. Orders whose wait time is over or with invalid prices are skipped.
  - Agreements that became idle while the app was down, or before the reaper got to them, release their offer here.
  - Cancelled offers and orders come back after a restart, because a cancel is not persisted.
    - If a user cancelled an order and placed a new one for the same model, only the older (cancelled) one comes back; the newer one is skipped as a duplicate and logged.
- The book holds entity snapshots: a provider error recorded after an offer entered the book is not seen. Cancel the offer (`MarketLogic.cancelOffer`) to take it out.
- Run on a single instance, because each instance would have its own book.
- The expiry queue drops matched or cancelled entries only when their expiry comes up, so an offer whose `offeredUntil` is far in the future keeps a small record in memory until then.