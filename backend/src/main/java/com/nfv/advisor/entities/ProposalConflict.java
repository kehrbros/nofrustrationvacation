package com.nfv.advisor.entities;

import com.nfv.incidents.entities.Impact;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

import java.time.Duration;

/** Incident still passed on the way of a proposal. */
@Getter
@Embeddable
public class ProposalConflict {
    @Column(nullable = false)
    private long incidentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Impact impact;
    private @Nullable Duration delay;

    protected ProposalConflict() {
    }

    public ProposalConflict(long incidentId, Impact impact, @Nullable Duration delay) {
        this.incidentId = incidentId;
        this.impact = impact;
        this.delay = delay;
    }
}
