package com.vacantagent.network.data;

import com.vacantagent.network.entities.Segment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SegmentRepository extends JpaRepository<Segment, Long> {
    List<Segment> findByRoadId(long roadId);

    Optional<Segment> findByRoadIdAndFromAndTo(long roadId, String from, String to);
}
