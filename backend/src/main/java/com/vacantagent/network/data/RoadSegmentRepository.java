package com.vacantagent.network.data;

import com.vacantagent.network.entities.RoadSegment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoadSegmentRepository extends JpaRepository<RoadSegment, Long> {
}