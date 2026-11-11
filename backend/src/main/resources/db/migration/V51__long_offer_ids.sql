-- LiteAPI offer ids can be longer than 1000 characters and must be stored whole, they are needed to book the offer.
ALTER TABLE advisor_proposal ALTER COLUMN stay_offer_id TYPE TEXT;
ALTER TABLE advisor_proposal_step ALTER COLUMN stay_offer_id TYPE TEXT;
ALTER TABLE booking_reservation ALTER COLUMN offer_id TYPE TEXT;
