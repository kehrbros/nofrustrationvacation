-- Accounting (credit/earnings accounts, top-ups, payouts) and provider onboarding of the former token market are
-- removed, see Docs/architecture.md#origin. Refunds of bookings stay in the module paypal.
DROP TABLE ledger_entry;
DROP TABLE accounting_account;
DROP TABLE payout_aud;
DROP TABLE payout;
DROP TABLE payout_account_aud;
DROP TABLE payout_account;
DROP TABLE payout_account_link_state;
DROP TABLE top_up_aud;
DROP TABLE top_up;
