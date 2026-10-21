package com.vacantagent.market.matching.entities;

import com.vacantagent.market.matching.MatchingStrategy;
import com.vacantagent.market.engine.QuadrantIndex;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
 * <p>Holds state only; what to do with an incoming entry is decided by a {@link MatchingStrategy}.
 * Not thread-safe: owned by the single matching thread.
 */
public final class OrderBook {

    private final QuadrantIndex<BookOffer> offers = new QuadrantIndex<>();
    private final QuadrantIndex<BookOrder> orders = new QuadrantIndex<>();
    private final Map<Long, QuadrantIndex.Entry<BookOffer>> offerById = new HashMap<>();
    private final Map<Long, QuadrantIndex.Entry<BookOrder>> orderById = new HashMap<>();
    private long seq;

    public void addOffer(BookOffer offer) {
        if (offerById.containsKey(offer.id())) throw new IllegalArgumentException("Duplicate offer id " + offer.id());
        offerById.put(offer.id(), offers.add(offer.priceInput(), offer.priceOutput(), seq++, offer));
    }

    public void addOrder(BookOrder order) {
        if (orderById.containsKey(order.id())) throw new IllegalArgumentException("Duplicate order id " + order.id());
        orderById.put(order.id(), orders.add(-order.maxInput(), -order.maxOutput(), seq++, order));
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
        return e != null && orders.remove(e);
    }

    public boolean hasOffer(long id) { return offerById.containsKey(id); }

    public boolean hasOrder(long id) { return orderById.containsKey(id); }

    public int offerCount() { return offers.size(); }

    public int orderCount() { return orders.size(); }
}