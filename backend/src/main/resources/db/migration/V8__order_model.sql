-- The model an order asks for; it only matches offers for the same provider and model.
ALTER TABLE consumer_order ADD COLUMN model_provider VARCHAR(255);
ALTER TABLE consumer_order ADD COLUMN model_name VARCHAR(255);
