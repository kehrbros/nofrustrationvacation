package com.vacantagent.market.data;

import com.vacantagent.market.entities.Agreement;
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
}