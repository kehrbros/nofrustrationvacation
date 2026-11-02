package com.nfv.auth.cimd;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * All clients of the IdP: the pre-registered ones from {@code spring.security.oauth2.authorizationserver.client.*}
 * and MCP clients identified by a metadata document URL. Deliberately not a {@link RegisteredClientRepository} bean,
 * otherwise Spring Boot would no longer register the configured clients.
 */
public class ClientRegistry {
    private final RegisteredClientRepository preRegistered;
    private final CimdClientResolver cimd;
    private final RegisteredClientRepository repository = new Repository();

    public ClientRegistry(RegisteredClientRepository preRegistered, CimdClientResolver cimd) {
        this.preRegistered = preRegistered;
        this.cimd = cimd;
    }

    /**
     * View for the authorization server.
     */
    public RegisteredClientRepository repository() {
        return repository;
    }

    public RegisteredClient findById(String id) {
        RegisteredClient client = preRegistered.findById(id);
        return client != null ? client : cimd.findById(id);
    }

    public RegisteredClient findByClientId(String clientId) {
        RegisteredClient client = preRegistered.findByClientId(clientId);
        return client != null ? client : cimd.findByClientId(clientId);
    }

    public static boolean isCimd(RegisteredClient client) {
        return Boolean.TRUE.equals(client.getClientSettings().getSetting(CimdClientResolver.SETTING_CIMD));
    }

    public static boolean isNative(RegisteredClient client) {
        return Boolean.TRUE.equals(client.getClientSettings().getSetting(CimdClientResolver.SETTING_NATIVE));
    }

    private final class Repository implements RegisteredClientRepository {

        @Override
        public void save(RegisteredClient registeredClient) {
            preRegistered.save(registeredClient);
        }

        @Override
        public RegisteredClient findById(String id) {
            return ClientRegistry.this.findById(id);
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return ClientRegistry.this.findByClientId(clientId);
        }
    }
}
