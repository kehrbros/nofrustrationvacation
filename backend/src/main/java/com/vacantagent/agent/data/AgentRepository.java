package com.vacantagent.agent.data;

import com.vacantagent.agent.entities.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    List<Agent> findByAppUserIdOrderByCreatedAtAscIdAsc(long appUserId);

    Optional<Agent> findByIdAndAppUserId(long id, long appUserId);

    boolean existsByAppUserId(long appUserId);
}
