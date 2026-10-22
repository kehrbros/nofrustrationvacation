-- Usage moves to the consumer order. Whether an agreement is still in use follows from
-- last_success_at (or agreed_at) + vacantagent.market.agreement-idle-timeout; there is no closed flag anymore.
ALTER TABLE consumer_order ADD COLUMN last_success_at TIMESTAMP(6) WITH TIME ZONE;
UPDATE consumer_order o SET last_success_at =
    (SELECT MAX(a.last_success_at) FROM agreement a WHERE a.consumer_order_id = o.id);
ALTER TABLE agreement DROP COLUMN last_success_at;
ALTER TABLE agreement DROP COLUMN last_error_at;
ALTER TABLE agreement_aud DROP COLUMN last_error_at;