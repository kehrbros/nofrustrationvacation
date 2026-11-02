package com.nfv.recommendations.data;

import com.nfv.recommendations.entities.SuggestionSet;
import com.nfv.recommendations.entities.SuggestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SuggestionSetRepository extends JpaRepository<SuggestionSet, Long> {
    /** Newest set of the route with the hash and status, to reuse instead of computing again. */
    Optional<SuggestionSet> findFirstByRouteIdAndBasisHashAndStatusOrderByCreatedAtDesc(
            long routeId, String basisHash, SuggestionStatus status);

    List<SuggestionSet> findByRouteIdAndStatus(long routeId, SuggestionStatus status);

    @Modifying
    @Query("DELETE FROM SuggestionSet s WHERE s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") OffsetDateTime before);
}