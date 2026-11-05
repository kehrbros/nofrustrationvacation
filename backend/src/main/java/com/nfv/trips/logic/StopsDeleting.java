package com.nfv.trips.logic;

import java.util.List;

/**
 * Published within the transaction right before stops are deleted, alone or with their trip. Modules that refer to
 * stops by id (bookings) delete what belongs to them; to stop the deletion, a listener throws
 * {@link StopInUseException}, which rolls the transaction back.
 */
public record StopsDeleting(long tripId, List<Long> stopIds) {
}
