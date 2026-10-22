package com.vacantagent.market.data;

import com.vacantagent.market.entities.Agreement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AgreementRepository extends JpaRepository<Agreement, Long> {

    /** Open, still valid agreements without a call since {@code cutoff}, oldest first. */
    @Query("""
            SELECT a.id FROM Agreement a
            WHERE a.lastErrorAt IS NULL
              AND (a.validUntil IS NULL OR a.validUntil > :now)
              AND COALESCE(a.lastSuccessAt, a.agreedAt) < :cutoff
            ORDER BY a.id
            """)
    List<Long> findIdleIds(OffsetDateTime now, OffsetDateTime cutoff);

    /** Locks the row until the transaction ends, so a concurrent {@link #markSuccess} waits or wins. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Agreement a WHERE a.id = :id")
    Optional<Agreement> findForUpdate(long id);

    /** @return 0 if the agreement does not exist or was already closed */
    @Transactional
    @Modifying
    @Query("UPDATE Agreement a SET a.lastSuccessAt = :at WHERE a.id = :id AND a.lastErrorAt IS NULL")
    int markSuccess(long id, OffsetDateTime at);
}