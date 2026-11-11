# Accounting

- **Module `com.nfv.accounting`:** domain accounting (accounts, ledger entries, top-up, payout). Legacy code from Nfv, use in the travel app undecided, see [architecture.md](architecture.md#legacy-code). The frontend pages for it (credit, earnings, payout account) have been removed. Uses `com.nfv.paypal` for payments, see [paypal.md](paypal.md). Amounts are always `Money` in USD, see [money.md](money.md).

## Accounts and ledger entries

- **Two accounts per user** (`Account`, table `accounting_account`, unique per `app_user_id` and `type`):
  - `CREDIT` (credit, consumer): topped up via PayPal, **not withdrawable**.
  - `EARNINGS` (earnings, provider): withdrawable via PayPal.
  - Reason for the separation: deposited money should not be withdrawable again directly (fees, money laundering risk).
- **Replaces `app_user.account_balance`:** `V11__accounting.sql` takes over existing balances as `CREDIT` with entry `OPENING_BALANCE` and drops the column. `GET /api/v1/users/me` no longer returns `accountBalance`, balances come from `GET /api/v1/accounting/balances`.
- **Ledger entries** (`LedgerEntry`): append only, never change; sum of entries = balance. Types: `TOP_UP`, `PAYOUT`, `PAYOUT_REVERSAL`, `USAGE_CHARGE`, `USAGE_EARNING`, `OPENING_BALANCE`. `referenceId` points to the top-up or payout. `USAGE_CHARGE`/`USAGE_EARNING` come from the removed market (reference: agreement) and remain only for existing entries.
- **`AccountLedger`:** `credit`/`debit` only within a transaction (`MANDATORY`). The balance changes with an atomic `UPDATE … SET balance = balance + :delta WHERE … AND balance + :delta >= 0`: no lost updates, never negative, no locks (test with parallel debits). Additionally DB constraint `type = 'CREDIT' OR balance >= 0` (`V14__credit_may_go_negative.sql`, for the removed usage billing, which was allowed to book `CREDIT` into the negative).

## Linking the payout account (provider)

- `PayoutAccount` (audited): `payer_id` from PayPal, email, verified yes/no. A PayPal account belongs to at most one user (`PAYPAL_ACCOUNT_IN_USE`).
- Flow:
  1. `POST /api/v1/accounting/payout-account/paypal/authorization` → a random `state` (32 bytes) is stored for the user, the response is the PayPal login URL.
  2. PayPal redirects back to `/paypal/connected?code=…&state=…` (frontend).
  3. `POST /api/v1/accounting/payout-account/paypal {code, state}`: `state` must belong to the same user, is valid for 15 min (`link-state-ttl`) and can be used only once (CSRF protection). Then exchange the code for account data.
- `DELETE /api/v1/accounting/payout-account` unlinks as long as no payout is open (`PAYOUTS_OPEN`).

## Top-up (consumer)

1. `POST /api/v1/accounting/top-ups {amount: {amount, currency}}`: amount in a supported currency with at most its decimal places. Conversion with `toBaseForBooking` (rate at most 4 days old), original amount and rate are stored (`OriginalAmount`). Limits 5–1000 USD (`min-top-up`, `max-top-up`). PayPal order over the original amount.
2. Frontend redirects to `approveUrl`, PayPal comes back to `/consumer/guthaben/paypal?token={orderId}` (cancelled: `&abgebrochen=1`).
3. `POST /top-ups/paypal/{orderId}/capture`: credited only on capture `COMPLETED` and matching amount, exactly once (row locked, status checked). `PENDING` is credited later by the poller. Declined → `FAILED`, `PAYMENT_DECLINED`.
- Status `TopUp`: `CREATED` → `COMPLETED` | `PENDING` → `COMPLETED` | `CANCELLED` | `FAILED`.

## Payout (provider)

1. `POST /api/v1/accounting/payouts {currency, amount?}`: without `amount` everything (earnings in the target currency rounded down, `MoneyRounding.PAYOUT`). At least 10 USD (`min-payout`), payout account required.
2. In one transaction: create `Payout` (`REQUESTED`) and debit `EARNINGS`. Only then call PayPal, so that nothing is ever paid out twice.
3. PayPal response: accepted → `SUBMITTED`; declined → `FAILED` + reversal `PAYOUT_REVERSAL`; outcome unknown (timeout/5xx) → `UNKNOWN`, stays debited, error in the log with `sender_batch_id` for manual review in the PayPal dashboard.
4. `PaymentStatusPoller` (every 5 min, `status-check-interval`): `SUCCESS` → `COMPLETED`; `FAILED`, `RETURNED`, `REFUNDED`, `REVERSED`, `BLOCKED`, `DENIED` → `FAILED` + reversal (exactly once).

## API errors

- Problem details with stable field `code`: `INVALID_AMOUNT`, `LINK_STATE_INVALID` (400), `INSUFFICIENT_BALANCE`, `PAYOUT_ACCOUNT_REQUIRED`, `PAYPAL_ACCOUNT_IN_USE`, `PAYOUTS_OPEN` (409), `AMOUNT_OUT_OF_RANGE`, `PAYMENT_NOT_APPROVED`, `PAYMENT_DECLINED` (422), `TOP_UP_NOT_FOUND` (404), `PAYPAL_ERROR` (502), `PAYPAL_NOT_CONFIGURED`, `EXCHANGE_RATE_UNAVAILABLE` (503). German texts for these were in the removed frontend module `src/accounting/problems.ts` (Git history).

## Configuration

```properties
nfv.accounting.min-top-up=5.00
nfv.accounting.max-top-up=1000.00
nfv.accounting.min-payout=10.00
nfv.accounting.link-state-ttl=15m
nfv.accounting.status-check-enabled=true   # false in tests
nfv.accounting.status-check-interval=5m
```

## Tests

- `AccountingFlowTest` (top-up, payout, linking, usage via the API, PayPal mocked), `AccountLedgerTest` (never negative, parallel debits), `PayPalClientTest`, `PayPalModuleTest`.

## Open

- Platform fee and PayPal fees.
- Clean up expired `CREATED` top-ups; resolve `UNKNOWN` payouts in the operator UI.
- Requirement for a verified PayPal account (stored, but not enforced).
