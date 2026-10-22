-- Owner of an order or offer. Existing rows keep NULL and can no longer be cancelled through the API.
ALTER TABLE consumer_order ADD COLUMN app_user_id BIGINT;
ALTER TABLE consumer_order ADD CONSTRAINT fk_consumer_order_app_user FOREIGN KEY (app_user_id) REFERENCES app_user (id);
ALTER TABLE provider_offer ADD COLUMN app_user_id BIGINT;
ALTER TABLE provider_offer ADD CONSTRAINT fk_provider_offer_app_user FOREIGN KEY (app_user_id) REFERENCES app_user (id);