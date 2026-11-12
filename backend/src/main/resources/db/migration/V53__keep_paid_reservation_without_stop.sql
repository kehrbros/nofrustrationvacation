-- A reservation booked and paid in the app stays when its stop is deleted (stop_id becomes NULL), so it can still be
-- cancelled with a refund from the to-do list, see Docs/domain.md#bookings. The to-do points to it.
ALTER TABLE booking_reservation ALTER COLUMN stop_id DROP NOT NULL;
ALTER TABLE booking_reservation DROP CONSTRAINT fk_booking_reservation_stop;
ALTER TABLE booking_reservation
    ADD CONSTRAINT fk_booking_reservation_stop FOREIGN KEY (stop_id) REFERENCES stop (id) ON DELETE SET NULL;

ALTER TABLE booking_todo ADD COLUMN reservation_id BIGINT;
ALTER TABLE booking_todo
    ADD CONSTRAINT fk_booking_todo_reservation FOREIGN KEY (reservation_id) REFERENCES booking_reservation (id)
        ON DELETE SET NULL;
CREATE INDEX ix_booking_todo_reservation ON booking_todo (reservation_id);

UPDATE booking_todo t SET reservation_id = r.id
FROM booking_reservation r
WHERE r.booking_id = t.booking_id AND t.booked_in_app;
