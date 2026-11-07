package com.nfv.advisor.logic;

import com.nfv.bookings.entities.BookingStatus;
import com.nfv.bookings.entities.BookingType;
import com.nfv.bookings.logic.AlternativeCriteria;
import com.nfv.bookings.logic.AlternativeSearch;
import com.nfv.bookings.logic.BookingLogic;
import com.nfv.bookings.logic.CriteriaProposal;
import com.nfv.bookings.logic.InvalidBookingInputException;
import com.nfv.bookings.logic.LiteApiUnavailableException;
import com.nfv.bookings.logic.SearchRateLimitedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link StayOptions} on the search for alternatives in {@code bookings}: the same hard check and limit per user, but
 * no booking needed and no ranking by the assistant, see Docs/advisor.md#search_accommodations.
 */
@Component
@RequiredArgsConstructor
class LiteApiStayOptions implements StayOptions {
    private final AlternativeSearch alternativeSearch;
    private final BookingLogic bookingLogic;

    @Override
    public boolean available() {
        return alternativeSearch.status().searchAvailable();
    }

    @Override
    public List<StayOffer> search(long appUserId, StaySearch search) {
        AlternativeCriteria criteria = new AlternativeCriteria(search.latitude(), search.longitude(),
                search.radiusMeters(), search.checkIn(), search.checkOut(), search.guests(), search.rooms(),
                search.maxPrice(), search.currency(), search.excludeHotelId());
        try {
            return alternativeSearch.offers(appUserId, criteria, search.maxResults()).stream()
                    .map(c -> new StayOffer(c.hotelId(), c.offerId(), c.name(), c.latitude(), c.longitude(),
                            c.distanceMeters(), c.stars(), c.rating(), c.boardName(), c.price(), c.currency(),
                            c.refundable(), c.freeCancellationUntil()))
                    .toList();
        } catch (InvalidBookingInputException e) {
            throw new InvalidStaySearchException(e.getMessage());
        } catch (SearchRateLimitedException e) {
            throw new StaySearchUnavailableException(e.getMessage(), true);
        } catch (LiteApiUnavailableException e) {
            throw new StaySearchUnavailableException(e.getMessage(), false);
        }
    }

    @Override
    public Optional<BookedStay> bookedStay(long appUserId, long tripId, long stopId) {
        return bookingLogic.list(appUserId, tripId).stream()
                .filter(view -> view.booking().getStopId() == stopId && view.accommodation() != null
                        && view.booking().getType() == BookingType.ACCOMMODATION
                        && view.booking().getStatus() == BookingStatus.CONFIRMED)
                .findFirst()
                .map(view -> {
                    long bookingId = view.booking().getId();
                    CriteriaProposal criteria = alternativeSearch.criteriaFor(appUserId, bookingId);
                    return new BookedStay(bookingId, criteria.latitude(), criteria.longitude(), criteria.checkIn(),
                            criteria.checkOut(), criteria.guests(), criteria.rooms(), criteria.maxPrice(),
                            criteria.currency(), criteria.excludeHotelId());
                });
    }
}
