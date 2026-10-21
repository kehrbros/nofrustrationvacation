package com.vacantagent.market.data;

import com.vacantagent.market.entities.ProviderOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProviderOfferRepository extends JpaRepository<ProviderOffer, Long> {

    /**
     * Offers that still have tokens and are not yet part of an agreement, in creation order.
     *
     * <p>TODO: an agreement may not use up all of an offer's tokens. Then the offer should re-enter the market
     * with its remaining tokens, so "has an agreement" can no longer exclude it here (and the 1:1 pairing
     * in the matching core needs revisiting).
     */
    @Query("""
            SELECT o FROM ProviderOffer o
            WHERE o.availableTokens > 0
              AND NOT EXISTS (SELECT a FROM Agreement a WHERE a.providerOffer = o)
            ORDER BY o.id
            """)
    List<ProviderOffer> findAllAvailable();
}