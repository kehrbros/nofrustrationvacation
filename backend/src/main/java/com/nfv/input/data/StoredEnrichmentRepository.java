package com.nfv.input.data;

import com.nfv.input.entities.StoredEnrichment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoredEnrichmentRepository extends JpaRepository<StoredEnrichment, Long> {
    Optional<StoredEnrichment> findBySourceIdAndSubjectIdAndMessageHashAndValidity(long sourceId, String subjectId,
                                                                                   String messageHash, String validity);
}
