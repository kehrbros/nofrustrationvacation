-- Start of an order's market wait time. Existing orders keep NULL and never expire.
ALTER TABLE consumer_order ADD COLUMN created_at TIMESTAMP(6) WITH TIME ZONE;