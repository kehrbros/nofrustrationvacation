package com.nfv.incidents.logic;

import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.Consequence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Marks consequences as resolved whose incident has ended: reported as ended by its source (also done right away by
 * the assessment) or its {@code validTo} has passed. Run by {@link ResolvedIncidentsScheduler}, see
 * Docs/domain.md#incidents.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolvedIncidents {
    private final ConsequenceRepository consequenceRepository;
    private final Clock clock;

    /** One pass, returns the number of consequences marked. */
    @Transactional
    public int markResolved() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Consequence> ended = consequenceRepository.findUnresolvedEnded(now);
        ended.forEach(consequence -> consequence.resolve(now));
        if (!ended.isEmpty()) {
            log.info("Marked {} consequences of ended incidents as resolved", ended.size());
        }
        return ended.size();
    }
}
