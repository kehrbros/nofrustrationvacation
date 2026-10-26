package com.vacantagent.agent.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.OffsetDateTime;

/**
 * LLM access of a provider whose spare capacity is sold on the market: the provider's own API key at an LLM vendor.
 * The key is stored encrypted and never leaves the backend, see Docs/agents.md.
 */
@Getter
@Entity
@Audited
public class Agent extends AbstractEntity {
    @Column(nullable = false)
    private long appUserId;
    /** Model sold with this key, e.g. {@code gpt-5}; offers of the agent sell this model. */
    @Column(nullable = false, length = 100)
    private String name;
    /** Vendor of the models, e.g. {@code openai}, same values as {@code modelProvider} of offers. */
    @Column(nullable = false, length = 50)
    private String modelProvider;
    /** API endpoint, {@code null} for the vendor's default. */
    private @Nullable String baseUrl;
    /** Encrypted with {@code SecretCipher}, not audited so no copy lands in the history. */
    @NotAudited
    @Column(nullable = false, length = 1024)
    private String apiKeyEncrypted;
    /** Last characters of the key, shown so the provider recognizes it. */
    @Column(nullable = false, length = 8)
    private String apiKeyHint;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected Agent() {
    }

    public Agent(long appUserId, String name, String modelProvider, @Nullable String baseUrl, OffsetDateTime createdAt) {
        this.appUserId = appUserId;
        this.name = name;
        this.modelProvider = modelProvider;
        this.baseUrl = baseUrl;
        this.createdAt = createdAt;
    }

    public void changeApiKey(String apiKeyEncrypted, String apiKeyHint) {
        this.apiKeyEncrypted = apiKeyEncrypted;
        this.apiKeyHint = apiKeyHint;
    }
}
