package com.vacantagent.input.data;

import com.vacantagent.input.entities.EventSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EventSourceRepository extends JpaRepository<EventSource, Long> {
    Optional<EventSource> findByName(String name);
}
