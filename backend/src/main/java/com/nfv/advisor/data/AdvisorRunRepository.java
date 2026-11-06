package com.nfv.advisor.data;

import com.nfv.advisor.entities.AdvisorRun;
import com.nfv.advisor.entities.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AdvisorRunRepository extends JpaRepository<AdvisorRun, Long> {
    Optional<AdvisorRun> findFirstByRouteIdOrderByIdDesc(long routeId);

    List<AdvisorRun> findByRouteIdAndStatusIn(long routeId, Collection<RunStatus> statuses);

    List<AdvisorRun> findByStatusIn(Collection<RunStatus> statuses);
}
