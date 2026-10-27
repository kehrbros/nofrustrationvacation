package com.vacantagent.llm.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;

import java.time.OffsetDateTime;

/** One prompt sent to a provider's agent through an agreement, see Docs/llm.md. Written once, never changed. */
@Getter
@Entity
public class LlmProtocol extends AbstractEntity {
    @Column(nullable = false)
    private long agentId;
    @Column(nullable = false)
    private long agreementId;
    @Column(nullable = false)
    private OffsetDateTime startedAt;
    @Column(nullable = false)
    private OffsetDateTime endedAt;
    /** Tokens consumed as reported by the agent's API, 0 for failed calls. */
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private boolean success;

    protected LlmProtocol() {
    }

    private LlmProtocol(long agentId, long agreementId, OffsetDateTime startedAt, OffsetDateTime endedAt,
                        long promptTokens, long completionTokens, long totalTokens, boolean success) {
        this.agentId = agentId;
        this.agreementId = agreementId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.success = success;
    }

    public static LlmProtocol succeeded(long agentId, long agreementId, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                        long promptTokens, long completionTokens, long totalTokens) {
        return new LlmProtocol(agentId, agreementId, startedAt, endedAt, promptTokens, completionTokens, totalTokens, true);
    }

    public static LlmProtocol failed(long agentId, long agreementId, OffsetDateTime startedAt, OffsetDateTime endedAt) {
        return new LlmProtocol(agentId, agreementId, startedAt, endedAt, 0, 0, 0, false);
    }
}