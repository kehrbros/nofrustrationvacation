package com.nfv.input.data;

import com.nfv.input.entities.SourceSubject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceSubjectRepository extends JpaRepository<SourceSubject, Long> {
    List<SourceSubject> findBySourceId(long sourceId);
}
