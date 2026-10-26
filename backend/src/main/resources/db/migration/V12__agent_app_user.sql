-- Owner of an agent: the owner of the offer it was created with.
ALTER TABLE agent ADD COLUMN app_user_id BIGINT;
UPDATE agent a SET app_user_id = (SELECT o.app_user_id FROM provider_offer o WHERE o.agent_id = a.id);
ALTER TABLE agent ADD CONSTRAINT fk_agent_app_user FOREIGN KEY (app_user_id) REFERENCES app_user (id);