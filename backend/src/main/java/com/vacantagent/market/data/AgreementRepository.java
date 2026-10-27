package com.vacantagent.market.data;

import com.vacantagent.market.entities.Agreement;
import jakarta.annotation.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface AgreementRepository extends JpaRepository<Agreement, Long> {

    /**
     * Still valid agreements whose last activity ({@code lastSuccessAt} of the order, else {@code agreedAt}) lies in
     * [fromCutoff, toCutoff), oldest first. With cutoff = t - idle timeout these are the agreements that became idle
     * in the window (from, to], see {@code Agreement#isIdleSince}.
     */
    @Query("""
            SELECT a.id FROM Agreement a JOIN a.consumerOrder o
            WHERE (a.validUntil IS NULL OR a.validUntil > :now)
              AND COALESCE(o.lastSuccessAt, a.agreedAt) >= :fromCutoff
              AND COALESCE(o.lastSuccessAt, a.agreedAt) < :toCutoff
            ORDER BY a.id
            """)
    List<Long> findIdleIds(OffsetDateTime now, OffsetDateTime fromCutoff, OffsetDateTime toCutoff);

    /**
     * Agreements of the consumer that may serve calls at {@code now}, newest first: still valid, not idle (see
     * {@link #findIdleIds}), their offer has tokens left or no limit. A {@code null} provider or model matches any.
     * Offer and provider are fetched, as callers read them outside the transaction.
     */
    @Query("""
            SELECT a FROM Agreement a JOIN FETCH a.consumerOrder c JOIN FETCH a.providerOffer o JOIN FETCH o.appUser
            WHERE c.appUser.id = :consumerAppUserId
              AND (a.validUntil IS NULL OR a.validUntil > :now)
              AND COALESCE(c.lastSuccessAt, a.agreedAt) >= :cutoff
              AND (o.availableTokens IS NULL OR o.availableTokens > 0)
              AND (:modelProvider IS NULL OR o.modelProvider = :modelProvider)
              AND (:modelName IS NULL OR o.modelName = :modelName)
            ORDER BY a.id DESC
            """)
    List<Agreement> findActive(long consumerAppUserId, @Nullable String modelProvider, @Nullable String modelName,
                               OffsetDateTime now, OffsetDateTime cutoff, Limit limit);
}