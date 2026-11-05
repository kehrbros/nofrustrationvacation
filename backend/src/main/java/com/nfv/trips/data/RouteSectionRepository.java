package com.nfv.trips.data;

import com.nfv.trips.entities.RouteSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface RouteSectionRepository extends JpaRepository<RouteSection, Long> {
    List<RouteSection> findByRouteIdOrderByPosition(long routeId);

    /** Sections on the segments whose expected passage overlaps {@code [from, to]}. */
    @Query("""
            SELECT s FROM RouteSection s
            WHERE s.segmentId IN :segmentIds AND s.expectedPassageEnd >= :from AND s.expectedPassageStart <= :to
            ORDER BY s.route.id, s.position""")
    List<RouteSection> findPassing(@Param("segmentIds") Collection<Long> segmentIds,
                                   @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** As {@link #findPassing} for an open end, every passage after {@code from}. */
    @Query("""
            SELECT s FROM RouteSection s
            WHERE s.segmentId IN :segmentIds AND s.expectedPassageEnd >= :from
            ORDER BY s.route.id, s.position""")
    List<RouteSection> findPassingAfter(@Param("segmentIds") Collection<Long> segmentIds,
                                        @Param("from") OffsetDateTime from);

    /** Segments of all sections whose expected passage overlaps {@code [from, to]}. */
    @Query("""
            SELECT DISTINCT s.segmentId FROM RouteSection s
            WHERE s.expectedPassageEnd >= :from AND s.expectedPassageStart <= :to""")
    List<Long> findSegmentIdsPassing(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM RouteSection s WHERE s.route.id = :routeId")
    int deleteByRouteId(@Param("routeId") long routeId);
}
