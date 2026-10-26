package com.vacantagent.market.data;

import com.vacantagent.market.entities.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    boolean existsByIdAndAppUserId(long id, long appUserId);
}