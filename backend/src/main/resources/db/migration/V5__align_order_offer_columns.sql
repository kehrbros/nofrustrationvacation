-- Brings consumer_order and provider_offer in line with the current entities.
-- Money columns are NUMERIC(19, 8) like all monetary values, see Docs/money.md.
ALTER TABLE consumer_order DROP COLUMN min_cost_per_million_tokens;
ALTER TABLE consumer_order DROP COLUMN max_cost_per_million_tokens;
ALTER TABLE consumer_order ADD COLUMN max_cost_per_million_input_tokens NUMERIC(19, 8);
ALTER TABLE consumer_order ADD COLUMN max_cost_per_million_output_tokens NUMERIC(19, 8);

ALTER TABLE provider_offer ADD COLUMN available_tokens BIGINT;
ALTER TABLE provider_offer ADD COLUMN total_tokens BIGINT;