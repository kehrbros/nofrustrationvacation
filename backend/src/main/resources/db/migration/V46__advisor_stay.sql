-- Offer of a STOPOVER or TARGET_STAY proposal of the agent, see Docs/advisor.md#search_accommodations.
-- Price in the currency of the offer like booking_reservation, see Docs/money.md.
ALTER TABLE advisor_proposal
    ADD COLUMN stay_hotel_id                VARCHAR(64),
    ADD COLUMN stay_offer_id                VARCHAR(1000),
    ADD COLUMN stay_hotel_name              VARCHAR(255),
    ADD COLUMN stay_latitude                DOUBLE PRECISION,
    ADD COLUMN stay_longitude               DOUBLE PRECISION,
    ADD COLUMN stay_place                   VARCHAR(255),
    ADD COLUMN stay_distance_meters         INTEGER,
    ADD COLUMN stay_check_in                DATE,
    ADD COLUMN stay_check_out               DATE,
    ADD COLUMN stay_guests                  INTEGER,
    ADD COLUMN stay_rooms                   INTEGER,
    ADD COLUMN stay_price                   NUMERIC(12, 2),
    ADD COLUMN stay_currency                VARCHAR(3),
    ADD COLUMN stay_refundable              BOOLEAN,
    ADD COLUMN stay_free_cancellation_until TIMESTAMP(6),
    ADD COLUMN stay_replaces_booking_id     BIGINT,
    ADD CONSTRAINT fk_advisor_proposal_stay_booking FOREIGN KEY (stay_replaces_booking_id) REFERENCES booking (id)
        ON DELETE SET NULL,
    ADD CONSTRAINT ck_advisor_proposal_stay CHECK (stay_hotel_id IS NULL OR (stay_offer_id IS NOT NULL
        AND stay_check_out > stay_check_in AND stay_price >= 0 AND stay_currency IS NOT NULL));
