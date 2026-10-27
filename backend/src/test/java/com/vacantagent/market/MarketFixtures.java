package com.vacantagent.market;

import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.user.entities.AppUser;
import org.springframework.test.util.ReflectionTestUtils;

/** Builds entities with a given id for tests that run without a database. */
public final class MarketFixtures {

    private MarketFixtures() {
    }

    public static BookOffer bookOffer(long id, long priceInput, long priceOutput) {
        return new BookOffer(withId(emptyOffer(), id), priceInput, priceOutput);
    }

    public static BookOrder bookOrder(long id, long maxInput, long maxOutput) {
        return new BookOrder(withId(emptyOrder(), id), maxInput, maxOutput);
    }

    /** Expires at {@code expiresAt} epoch millis. */
    public static BookOffer bookOffer(long id, long priceInput, long priceOutput, long expiresAt) {
        return new BookOffer(withId(emptyOffer(), id), priceInput, priceOutput, expiresAt);
    }

    /** Expires at {@code expiresAt} epoch millis. */
    public static BookOrder bookOrder(long id, long maxInput, long maxOutput, long expiresAt) {
        return new BookOrder(withId(emptyOrder(), id), maxInput, maxOutput, expiresAt);
    }

    /** An order of user {@code appUserId} for one provider + model; {@code null} means any. */
    public static BookOrder ownedBookOrder(long id, long maxInput, long maxOutput, long appUserId,
                                           String modelProvider, String modelName) {
        AppUser owner = withId(AppUser.firstLogin("user-" + appUserId, null, null), appUserId);
        return new BookOrder(withId(new ConsumerOrder(null, null, null, null, modelProvider, modelName, null, owner), id),
                maxInput, maxOutput);
    }

    public static ProviderOffer emptyOffer() {
        return offer(0, null, null);
    }

    /** An offer of agent {@code agentId} for one provider + model, everything else empty. */
    public static ProviderOffer offer(long agentId, String modelProvider, String modelName) {
        return new ProviderOffer(null, null, null, agentId, modelProvider, modelName, null, null, null, null, null);
    }

    public static ConsumerOrder emptyOrder() {
        return new ConsumerOrder(null, null, null, null, null, null, null, null);
    }

    public static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}