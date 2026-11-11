package com.nfv.advisor.data;

import com.nfv.advisor.entities.AdvisorPlanExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdvisorPlanExecutionRepository extends JpaRepository<AdvisorPlanExecution, Long> {
    Optional<AdvisorPlanExecution> findByPlanPaymentId(long planPaymentId);
}
