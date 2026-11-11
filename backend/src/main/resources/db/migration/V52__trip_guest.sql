-- Guest data remembered for the bookings of a trip (only at the user's wish). Personal data: not in trip_aud.
ALTER TABLE trip ADD COLUMN guest_first_name VARCHAR(100);
ALTER TABLE trip ADD COLUMN guest_last_name VARCHAR(100);
ALTER TABLE trip ADD COLUMN guest_email VARCHAR(255);
ALTER TABLE trip ADD COLUMN guest_phone VARCHAR(64);
