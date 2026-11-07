-- Booking of alternatives: owner for the background completion of open reservations, cancellation with PayPal refund,
-- see Docs/paypal.md#stabilität and Docs/accommodation-alternatives.md.

ALTER TABLE booking_reservation ADD COLUMN app_user_id BIGINT;
UPDATE booking_reservation r SET app_user_id = t.app_user_id
FROM stop s JOIN trip t ON t.id = s.trip_id
WHERE s.id = r.stop_id;
ALTER TABLE booking_reservation ALTER COLUMN app_user_id SET NOT NULL;

-- last time a request or the background job worked on the reservation; the job leaves fresh ones to the user's request
ALTER TABLE booking_reservation ADD COLUMN attempted_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE booking_reservation ADD COLUMN cancelled_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE booking_reservation ADD COLUMN refund_amount NUMERIC(12, 2);
ALTER TABLE booking_reservation ADD COLUMN paypal_refund_id VARCHAR(64);

CREATE INDEX ix_booking_reservation_status ON booking_reservation (status);
