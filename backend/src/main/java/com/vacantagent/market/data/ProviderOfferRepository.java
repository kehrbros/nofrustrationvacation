package com.vacantagent.market.data;

import com.vacantagent.market.entities.ProviderOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface ProviderOfferRepository extends JpaRepository<ProviderOffer, Long> {

    /**
     * Offers that still have tokens and are not part of an active agreement at {@code now}, in creation order.
     * An agreement is active while still valid and not idle: its last activity ({@code lastSuccessAt} of the order,
     * else {@code agreedAt}) is at or after {@code cutoff} = now - idle timeout, see {@code Agreement#isIdleSince}.
     *
     * <p>TODO: an agreement may not use up all of an offer's tokens. Then the offer should re-enter the market
     * with its remaining tokens, so "has an agreement" can no longer exclude it here (and the 1:1 pairing
     * in the matching core needs revisiting).
     */
    @Query("""
            SELECT o FROM ProviderOffer o
            WHERE o.availableTokens > 0
              AND NOT EXISTS (SELECT a FROM Agreement a JOIN a.consumerOrder c
                              WHERE a.providerOffer = o
                                AND (a.validUntil IS NULL OR a.validUntil > :now)
                                AND COALESCE(c.lastSuccessAt, a.agreedAt) >= :cutoff)
            ORDER BY o.id
            """)
    List<ProviderOffer> findAllAvailable(OffsetDateTime now, OffsetDateTime cutoff);

    boolean existsByIdAndAppUserId(long id, long appUserId);

    /**
     * Subtracts {@code tokens} from the available tokens (never below zero, {@code null} stays unlimited) and sets
     * {@code lastSuccessAt}, in one statement, so concurrent calls on the offer never lose an update.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ProviderOffer o SET o.lastSuccessAt = :at,
                o.availableTokens = CASE WHEN o.availableTokens IS NULL THEN o.availableTokens
                                         WHEN o.availableTokens > :tokens THEN o.availableTokens - :tokens
                                         ELSE 0 END
            WHERE o.id = :id
            """)
    int recordSuccess(long id, long tokens, OffsetDateTime at);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ProviderOffer o SET o.lastErrorAt = :at WHERE o.id = :id")
    int recordError(long id, OffsetDateTime at);
}