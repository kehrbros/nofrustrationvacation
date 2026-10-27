-- LLM usage is charged after delivery, so a consumer's CREDIT may go below zero, see Docs/accounting.md.
-- EARNINGS still never go below zero.
ALTER TABLE accounting_account DROP CONSTRAINT ck_accounting_account_balance;
ALTER TABLE accounting_account ADD CONSTRAINT ck_accounting_account_balance CHECK (type = 'CREDIT' OR balance >= 0);