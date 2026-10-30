package com.vacantagent.network.data;

import com.vacantagent.network.entities.Road;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoadRepository extends JpaRepository<Road, Long> {
    Optional<Road> findByNumber(String number);
}
