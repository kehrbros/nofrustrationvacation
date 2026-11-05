-- Number of travelers of a trip, suggested as guests when adding a booking (optional, 1-99).
ALTER TABLE trip ADD COLUMN travelers INTEGER;
ALTER TABLE trip ADD CONSTRAINT trip_travelers_range CHECK (travelers BETWEEN 1 AND 99);
ALTER TABLE trip_aud ADD COLUMN travelers INTEGER;
