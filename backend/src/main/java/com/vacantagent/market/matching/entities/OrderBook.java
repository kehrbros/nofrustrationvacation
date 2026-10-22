package com.vacantagent.market.matching.entities;

import com.vacantagent.market.matching.MatchingStrategy;
import com.vacantagent.market.engine.QuadrantIndex;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * Waiting offers and waiting orders, each in a {@link QuadrantIndex}.
 *
 * <ul>
 *   <li>Offers are stored at (priceInput, priceOutput). "priceInput <= maxInput and priceOutput <= maxOutput,
 *       cheapest total first" is exactly the index query.</li>
 *   <li>Orders are stored at (-maxInput, -maxOutput). Negating turns "maxInput >= priceInput and
 *       maxOutput >= priceOutput, highest total first" into the same query.</li>
 * </ul>
 * Arrival order is kept with a sequence number, so equal totals go to the oldest entry.
 *
 * <p>Entries that expire are also kept in a queue by expiry, so {@link #removeExpiredOffers} and
 * {@link #removeExpiredOrders} do not scan the book. Matched or cancelled entries are dropped from that queue
 * lazily, when their expiry comes up.
 *
 * <p>Holds state only; what to do with an incoming entry is decided by a {@link MatchingStrategy}.
 * Not thread-safe: owned by the single matching thread.
 */
public final class OrderBook {

    /** Expiry of entries that never expire. */
    public static final long NO_EXPIRY = Long.MAX_VALUE;

    private record Expiry(long at, long id) {}

    private final QuadrantIndex<BookOffer> offers = new QuadrantIndex<>();
    private final QuadrantIndex<BookOrder> orders = new QuadrantIndex<>();
    private final Map<Long, QuadrantIndex.Entry<BookOffer>> offerById = new HashMap<>();
    private final Map<Long, QuadrantIndex.Entry<BookOrder>> orderById = new HashMap<>();
    /** Waiting orders that have an owner, see {@link BookOrder#ownerModelKey()}. */
    private final Map<BookOrder.OwnerModelKey, Long> orderIdByOwnerModel = new HashMap<>();
    private final PriorityQueue<Expiry> offerExpiries = new PriorityQueue<>(Comparator.comparingLong(Expiry::at));
    private final PriorityQueue<Expiry> orderExpiries = new PriorityQueue<>(Comparator.comparingLong(Expiry::at));
    private long seq;

    public void addOffer(BookOffer offer) {
        if (offerById.containsKey(offer.id())) throw new IllegalArgumentException("Duplicate offer id " + offer.id());
        offerById.put(offer.id(), offers.add(offer.priceInput(), offer.priceOutput(), seq++, offer));
        if (offer.expiresAt() != NO_EXPIRY) offerExpiries.add(new Expiry(offer.expiresAt(), offer.id()));
    }

    public void addOrder(BookOrder order) {
        if (orderById.containsKey(order.id())) throw new IllegalArgumentException("Duplicate order id " + order.id());
        BookOrder.OwnerModelKey key = order.ownerModelKey();
        if (key != null && orderIdByOwnerModel.containsKey(key)) {
            throw new IllegalArgumentException("Order " + orderIdByOwnerModel.get(key) + " already waits for " + key);
        }
        orderById.put(order.id(), orders.add(-order.maxInput(), -order.maxOutput(), seq++, order));
        if (key != null) orderIdByOwnerModel.put(key, order.id());
        if (order.expiresAt() != NO_EXPIRY) orderExpiries.add(new Expiry(order.expiresAt(), order.id()));
    }

    /** The waiting order of the same owner for the same provider and model, if any. Empty for orders without owner. */
    public Optional<BookOrder> waitingOrderLike(BookOrder order) {
        BookOrder.OwnerModelKey key = order.ownerModelKey();
        Long id = (key == null) ? null : orderIdByOwnerModel.get(key);
        return (id == null) ? Optional.empty() : Optional.of(orderById.get(id).value());
    }

    /** Cheapest waiting offer (by total, then oldest) that fits both of the order's limits and the filter. */
    public Optional<BookOffer> bestOfferFor(BookOrder order, Predicate<? super BookOffer> filter) {
        return offers.findBest(order.maxInput(), order.maxOutput(), filter).map(QuadrantIndex.Entry::value);
    }

    /** Highest-paying waiting order (by total, then oldest) that accepts both of the offer's prices and the filter. */
    public Optional<BookOrder> bestOrderFor(BookOffer offer, Predicate<? super BookOrder> filter) {
        return orders.findBest(-offer.priceInput(), -offer.priceOutput(), filter).map(QuadrantIndex.Entry::value);
    }

    public boolean removeOffer(long id) {
        QuadrantIndex.Entry<BookOffer> e = offerById.remove(id);
        return e != null && offers.remove(e);
    }

    public boolean removeOrder(long id) {
        QuadrantIndex.Entry<BookOrder> e = orderById.remove(id);
        if (e == null) return false;
        BookOrder.OwnerModelKey key = e.value().ownerModelKey();
        if (key != null) orderIdByOwnerModel.remove(key);
        return orders.remove(e);
    }

    /** Removes every waiting offer with {@code expiresAt <= nowMillis}; returns how many. */
    public int removeExpiredOffers(long nowMillis) {
        int removed = 0;
        while (!offerExpiries.isEmpty() && offerExpiries.peek().at() <= nowMillis) {
            Expiry e = offerExpiries.poll();
            QuadrantIndex.Entry<BookOffer> entry = offerById.get(e.id());
            // The id may have left and re-entered the book since; only remove the entry this expiry belongs to.
            if (entry != null && entry.value().expiresAt() == e.at() && removeOffer(e.id())) removed++;
        }
        return removed;
    }

    /** Removes every waiting order with {@code expiresAt <= nowMillis}; returns how many. */
    public int removeExpiredOrders(long nowMillis) {
        int removed = 0;
        while (!orderExpiries.isEmpty() && orderExpiries.peek().at() <= nowMillis) {
            Expiry e = orderExpiries.poll();
            QuadrantIndex.Entry<BookOrder> entry = orderById.get(e.id());
            if (entry != null && entry.value().expiresAt() == e.at() && removeOrder(e.id())) removed++;
        }
        return removed;
    }

    public boolean hasOffer(long id) { return offerById.containsKey(id); }

    public boolean hasOrder(long id) { return orderById.containsKey(id); }

    public int offerCount() { return offers.size(); }

    public int orderCount() { return orders.size(); }
}