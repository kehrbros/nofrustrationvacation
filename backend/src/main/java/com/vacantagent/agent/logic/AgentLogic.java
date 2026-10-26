package com.vacantagent.agent.logic;

import com.vacantagent.agent.data.AgentRepository;
import com.vacantagent.agent.entities.Agent;
import com.vacantagent.common.crypto.SecretCipher;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Agents of providers. API keys are encrypted before they are stored; only the LLM proxy reads them in plain text
 * via {@link #apiKey(long)}.
 */
@Service
@RequiredArgsConstructor
public class AgentLogic {
    private static final int HINT_LENGTH = 4;
    private static final int MIN_KEY_LENGTH = 8;

    private final AgentRepository agentRepository;
    private final SecretCipher secretCipher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Agent> agents(long appUserId) {
        return agentRepository.findByAppUserIdOrderByCreatedAtAscIdAsc(appUserId);
    }

    @Transactional(readOnly = true)
    public boolean hasAgent(long appUserId) {
        return agentRepository.existsByAppUserId(appUserId);
    }

    /** False if the agent does not exist or belongs to another user. */
    @Transactional(readOnly = true)
    public boolean isOwnedBy(long appUserId, long agentId) {
        return agentRepository.findByIdAndAppUserId(agentId, appUserId).isPresent();
    }

    /** Empty if the agent does not exist or belongs to another user. */
    @Transactional(readOnly = true)
    public Optional<Agent> findOwned(long appUserId, long agentId) {
        return agentRepository.findByIdAndAppUserId(agentId, appUserId);
    }

    @Transactional
    public Agent create(long appUserId, String name, String modelProvider, @Nullable String baseUrl, String apiKey) {
        Agent agent = new Agent(appUserId, required(name, "name"), required(modelProvider, "modelProvider").toLowerCase(),
                validBaseUrl(baseUrl), OffsetDateTime.now(clock));
        setApiKey(agent, apiKey);
        return agentRepository.save(agent);
    }

    @Transactional
    public Optional<Agent> changeApiKey(long appUserId, long agentId, String apiKey) {
        return agentRepository.findByIdAndAppUserId(agentId, appUserId).map(agent -> {
            setApiKey(agent, apiKey);
            return agent;
        });
    }

    /**
     * @return false if the agent does not exist or belongs to another user
     * @throws AgentInUseException if offers reference the agent (foreign key of {@code provider_offer})
     */
    @Transactional
    public boolean delete(long appUserId, long agentId) {
        return agentRepository.findByIdAndAppUserId(agentId, appUserId).map(agent -> {
            try {
                agentRepository.delete(agent);
                agentRepository.flush();
            } catch (DataIntegrityViolationException e) {
                throw new AgentInUseException(agentId);
            }
            return true;
        }).orElse(false);
    }

    /** Plain API key, for calls to the vendor only. Never log or return it. */
    @Transactional(readOnly = true)
    public String apiKey(long agentId) {
        return secretCipher.decrypt(agentRepository.findById(agentId).orElseThrow().getApiKeyEncrypted());
    }

    private void setApiKey(Agent agent, String apiKey) {
        String key = required(apiKey, "apiKey");
        if (key.length() < MIN_KEY_LENGTH) {
            throw new InvalidAgentException("apiKey is too short");
        }
        agent.changeApiKey(secretCipher.encrypt(key), key.substring(key.length() - HINT_LENGTH));
    }

    private static String required(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidAgentException(field + " is required");
        }
        return value.strip();
    }

    private static @Nullable String validBaseUrl(@Nullable String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(baseUrl.strip());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new InvalidAgentException("baseUrl must be an https URL");
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw new InvalidAgentException("baseUrl is not a URL");
        }
    }
}
