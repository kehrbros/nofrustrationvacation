-- An agreement the consumer leaves unused is closed (last_error_at) and its offer goes back into the market.
-- last_success_at changes on every call and is not audited.
ALTER TABLE agreement ADD COLUMN last_error_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE agreement ADD COLUMN last_success_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE agreement_aud ADD COLUMN last_error_at TIMESTAMP(6) WITH TIME ZONE;