package com.nfv.network.data;

import com.nfv.network.entities.RoadSegment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoadSegmentRepository extends JpaRepository<RoadSegment, Long> {
}