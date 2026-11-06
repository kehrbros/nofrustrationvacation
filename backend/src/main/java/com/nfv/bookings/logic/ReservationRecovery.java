package com.nfv.bookings.logic;

import com.nfv.bookings.config.AlternativesProperties;
import com.nfv.bookings.entities.BookingReservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Continues reservations nobody finished: the user approved at PayPal but never came back to the app (closed it, lost
 * the connection, the PayPal window ended up in another browser), the answer of a step got lost, or a refund failed.
 * Without this, money would stay reserved at PayPal until the authorization expires. See Docs/paypal.md#stabilität.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationRecovery {
    private final ReservationSteps steps;
    private final AlternativeReservations reservations;
    private final AlternativesProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${nfv.bookings.alternatives.recovery-interval:60s}",
            initialDelayString = "${nfv.bookings.alternatives.recovery-interval:60s}")
    void scheduled() {
        if (properties.recoveryEnabled()) {
            runOnce();
        }
    }

    /** One pass over all unfinished reservations, except those a request worked on within the grace period. */
    public void runOnce() {
        OffsetDateTime limit = OffsetDateTime.now(clock).minus(properties.recoveryGrace());
        for (BookingReservation reservation : steps.unfinished()) {
            OffsetDateTime last = reservation.getAttemptedAt() != null ? reservation.getAttemptedAt()
                    : reservation.getCreatedAt();
            if (last.isBefore(limit)) {
                reservations.recover(reservation.getId());
            }
        }
    }
}
