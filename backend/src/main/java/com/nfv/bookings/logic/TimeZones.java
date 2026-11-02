package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.iakovlev.timeshape.TimeZoneEngine;
import org.springframework.stereotype.Component;

import java.lang.ref.SoftReference;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Time zone of a place: from its coordinates (timeshape, OpenStreetMap boundaries, offline), else the zone the user
 * sent, else UTC. The boundary data is loaded on first use (some 4 s, about 200 MB heap) and held softly, so the
 * garbage collector may drop it when memory runs short; the next lookup loads it again.
 */
@Slf4j
@Component
class TimeZones {
    private volatile SoftReference<TimeZoneEngine> engine = new SoftReference<>(null);

    ZoneId of(@Nullable Double latitude, @Nullable Double longitude, @Nullable String fallback) {
        if (latitude != null && longitude != null) {
            var zone = engine().query(latitude, longitude);
            if (zone.isPresent()) {
                return zone.get();
            }
        }
        if (fallback != null && !fallback.isBlank()) {
            try {
                return ZoneId.of(fallback.strip());
            } catch (DateTimeException e) {
                throw new InvalidBookingInputException("unknown time zone " + fallback);
            }
        }
        return ZoneOffset.UTC;
    }

    private TimeZoneEngine engine() {
        TimeZoneEngine current = engine.get();
        if (current == null) {
            synchronized (this) {
                current = engine.get();
                if (current == null) {
                    long start = System.nanoTime();
                    current = TimeZoneEngine.initialize();
                    engine = new SoftReference<>(current);
                    log.info("Time zone boundaries loaded in {} ms", (System.nanoTime() - start) / 1_000_000);
                }
            }
        }
        return current;
    }
}
