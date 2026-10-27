-- Offers are served by an agent of the agent module, whose name is the model it sells.
-- provider_offer keeps model_provider and model_name as a copy of the agent's: matching reads them without I/O.
ALTER TABLE provider_offer ADD COLUMN agent_id BIGINT;
ALTER TABLE provider_offer ADD CONSTRAINT fk_provider_offer_agent FOREIGN KEY (agent_id) REFERENCES agent (id);