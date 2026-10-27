package com.vacantagent.llm.data;

import com.vacantagent.llm.entities.LlmProtocol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmProtocolRepository extends JpaRepository<LlmProtocol, Long> {
    List<LlmProtocol> findByAgreementIdOrderByIdAsc(long agreementId);
}