package com.nfv.bookings.logic;

import com.nfv.bookings.config.AlternativesProperties;
import com.nfv.bookings.entities.AccommodationDetails;
import com.nfv.bookings.entities.Booking;
import com.nfv.paypal.PayPalCheckout;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Search for alternatives to an accommodation booking: criteria from the booking, offers from LiteAPI, hard check of
 * every offer against all criteria here, ranking by the assistant, see Docs/accommodation-alternatives.md. Runs
 * without transaction, the calls to LiteAPI and the model take seconds.
 */
@Service
@RequiredArgsConstructor
public class AlternativeSearch {
    static final String PLACE_REF_PREFIX = "liteapi:hotel/";
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int SAME_PLACE_METERS = 50;
    private static final int MAX_RADIUS = 50_000;
    private static final int MAX_NIGHTS = 30;

    private final BookingLogic bookingLogic;
    private final TripLogic tripLogic;
    private final LiteApi liteApi;
    private final AlternativeRanking ranking;
    private final SearchLimiter limiter;
    private final AlternativesProperties properties;
    private final PayPalCheckout checkout;
    private final Clock clock;

    /** Search needs LiteAPI, booking also PayPal; ranking needs the assistant. */
    public Status status() {
        return new Status(liteApi.isAvailable(), liteApi.isAvailable() && checkout.isConfigured(),
                ranking.isAvailable());
    }

    /** Criteria for the use case "booking cannot be used": everything from the booking, see the concept. */
    public CriteriaProposal criteriaFor(long appUserId, long bookingId) {
        BookingView view = bookingLogic.accommodation(appUserId, bookingId);
        Booking booking = view.booking();
        AccommodationDetails details = view.accommodation();
        List<String> missing = new ArrayList<>();

        Double latitude = details.getLatitude();
        Double longitude = details.getLongitude();
        String placeName = details.getAddressLine() == null ? details.getName() : details.getAddressLine();
        if (latitude == null) {
            Stop stop = tripLogic.stop(appUserId, booking.getStopId()).orElse(null);
            if (stop != null && stop.getLatitude() != null) {
                latitude = stop.getLatitude();
                longitude = stop.getLongitude();
                placeName = stop.getName();
            } else {
                placeName = null;
                missing.add("location");
            }
        }

        ZoneId zone = ZoneId.of(details.getTimeZone());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate bookedIn = booking.getStartsAt() == null ? null
                : booking.getStartsAt().atZoneSameInstant(zone).toLocalDate();
        LocalDate checkOut = booking.getEndsAt() == null ? null
                : booking.getEndsAt().atZoneSameInstant(zone).toLocalDate();
        // a stay that is running already: from today on
        LocalDate checkIn = bookedIn != null && bookedIn.isBefore(today) ? today : bookedIn;
        if (checkIn == null) {
            missing.add("checkIn");
        }
        if (checkOut == null || checkIn != null && !checkOut.isAfter(checkIn)) {
            checkOut = null;
            missing.add("checkOut");
        }
        if (details.getGuests() == null) {
            missing.add("guests");
        }

        BigDecimal maxPrice = booking.getPrice();
        if (maxPrice != null && bookedIn != null && checkIn != null && checkOut != null && booking.getEndsAt() != null) {
            long bookedNights = ChronoUnit.DAYS.between(bookedIn, booking.getEndsAt().atZoneSameInstant(zone).toLocalDate());
            long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
            if (bookedNights > 0 && nights < bookedNights) {
                maxPrice = maxPrice.multiply(BigDecimal.valueOf(nights))
                        .divide(BigDecimal.valueOf(bookedNights), 2, RoundingMode.UP);
            }
        }
        String placeRef = details.getPlaceRef();
        return new CriteriaProposal(latitude, longitude, placeName, properties.defaultRadius(), checkIn, checkOut,
                details.getGuests(), details.getRooms() == null ? 1 : details.getRooms(), maxPrice,
                booking.getCurrency() == null ? "EUR" : booking.getCurrency(),
                placeRef != null && placeRef.startsWith(PLACE_REF_PREFIX) ? placeRef.substring(PLACE_REF_PREFIX.length()) : null,
                List.copyOf(missing));
    }

    /** Offers that meet all criteria, best first: ranked by the assistant if available, else by price. */
    public SearchResult search(long appUserId, long bookingId, AlternativeCriteria criteria) {
        BookingView view = bookingLogic.accommodation(appUserId, bookingId);
        Offers found = find(appUserId, criteria, properties.maxCandidates());
        List<AlternativeCandidate> ranked = ranking.rank(appUserId, view.booking(), view.accommodation(),
                found.candidates());
        boolean byAssistant = ranked.stream().anyMatch(candidate -> candidate.reason() != null);
        return new SearchResult(ranked, byAssistant && !ranked.isEmpty() ? ranked.getFirst().hotelId() : null,
                byAssistant, found.checked());
    }

    /**
     * Offers that meet all criteria, cheapest first, without a booking and without the assistant: for the agent, whose
     * tool must not call the model again, see Docs/advisor.md#search_accommodations. Same hard check and limit.
     *
     * @param maxResults at most this many, never more than {@code max-candidates}
     * @throws InvalidBookingInputException criteria out of range
     * @throws SearchRateLimitedException   searches of the user used up
     * @throws LiteApiUnavailableException  LiteAPI not configured or failing
     */
    public List<AlternativeCandidate> offers(long appUserId, AlternativeCriteria criteria, int maxResults) {
        return find(appUserId, criteria, Math.min(maxResults, properties.maxCandidates())).candidates();
    }

    private record Offers(List<AlternativeCandidate> candidates, int checked) {
    }

    private Offers find(long appUserId, AlternativeCriteria criteria, int limit) {
        validate(criteria);
        limiter.acquire(appUserId);
        List<LiteApi.HotelOffer> offers = liteApi.rates(new LiteApi.RateQuery(criteria.latitude(), criteria.longitude(),
                criteria.radiusMeters(), criteria.checkIn(), criteria.checkOut(),
                Pricing.adultsPerRoom(criteria.guests(), criteria.rooms()), criteria.currency()));

        Map<String, AlternativeCandidate> cheapest = new LinkedHashMap<>();
        for (LiteApi.HotelOffer offer : offers) {
            AlternativeCandidate candidate = check(offer, criteria);
            if (candidate == null) {
                continue;
            }
            cheapest.merge(candidate.hotelId(), candidate,
                    (existing, other) -> other.price().compareTo(existing.price()) < 0 ? other : existing);
        }
        List<AlternativeCandidate> candidates = cheapest.values().stream()
                .sorted(Comparator.comparing(AlternativeCandidate::price)
                        .thenComparingInt(AlternativeCandidate::distanceMeters))
                .limit(limit)
                .toList();
        return new Offers(candidates, offers.size());
    }

    /** The hard check: the candidate with our price, or null if any criterion is not met. */
    @Nullable AlternativeCandidate check(LiteApi.HotelOffer offer, AlternativeCriteria criteria) {
        if (!offer.currency().equalsIgnoreCase(criteria.currency()) || offer.hotelId().equals(criteria.excludeHotelId())) {
            return null;
        }
        int distance = Pricing.distanceMeters(criteria.latitude(), criteria.longitude(), offer.latitude(), offer.longitude());
        if (distance > criteria.radiusMeters()) {
            return null;
        }
        BigDecimal price = Pricing.price(offer.netPrice(), offer.suggestedSellingPrice(), properties.feePercent());
        if (criteria.maxPrice() != null && price.compareTo(criteria.maxPrice()) > 0) {
            return null;
        }
        return new AlternativeCandidate(offer.hotelId(), offer.name(), offer.address(), offer.latitude(),
                offer.longitude(), distance, offer.stars(), offer.rating(), offer.photoUrl(), offer.offerId(),
                offer.roomName(), offer.boardName(), offer.refundable(), offer.freeCancellationUntil(), price,
                offer.currency().toUpperCase(), distance <= SAME_PLACE_METERS, null);
    }

    void validate(AlternativeCriteria criteria) {
        if (Math.abs(criteria.latitude()) > 90 || Math.abs(criteria.longitude()) > 180) {
            throw new InvalidBookingInputException("coordinates out of range");
        }
        if (criteria.radiusMeters() < 100 || criteria.radiusMeters() > MAX_RADIUS) {
            throw new InvalidBookingInputException("radius must be 100 m to 50 km");
        }
        if (criteria.checkIn() == null || criteria.checkOut() == null || !criteria.checkOut().isAfter(criteria.checkIn())) {
            throw new InvalidBookingInputException("check-out must be after check-in");
        }
        // one day of tolerance: the date at the place may be behind the server's
        if (criteria.checkIn().isBefore(LocalDate.now(clock).minusDays(1))) {
            throw new InvalidBookingInputException("check-in must not be in the past");
        }
        if (ChronoUnit.DAYS.between(criteria.checkIn(), criteria.checkOut()) > MAX_NIGHTS) {
            throw new InvalidBookingInputException("at most " + MAX_NIGHTS + " nights");
        }
        if (criteria.rooms() < 1 || criteria.rooms() > 9 || criteria.guests() < criteria.rooms() || criteria.guests() > 36) {
            throw new InvalidBookingInputException("1 to 9 rooms with at least one guest each");
        }
        if (criteria.maxPrice() != null && criteria.maxPrice().signum() <= 0) {
            throw new InvalidBookingInputException("maximum price must be positive");
        }
        if (criteria.currency() == null || !CURRENCY.matcher(criteria.currency()).matches()) {
            throw new InvalidBookingInputException("currency must be ISO 4217");
        }
    }

    /**
     * @param bookingAvailable LiteAPI and PayPal configured
     * @param rankingAvailable the assistant ranks and explains
     */
    public record Status(boolean searchAvailable, boolean bookingAvailable, boolean rankingAvailable) {
    }

    /**
     * @param recommendedHotelId the assistant's pick, null without ranking
     * @param offersChecked      offers LiteAPI returned before the hard check
     */
    public record SearchResult(List<AlternativeCandidate> candidates, @Nullable String recommendedHotelId,
                               boolean ranked, int offersChecked) {
    }
}
