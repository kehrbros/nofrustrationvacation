package com.vacantagent.auth.cimd;

import java.net.URI;
import java.util.List;

/**
 * The validated parts of a Client ID Metadata Document.
 *
 * @param clientId     URL of the document, equal to its {@code client_id}
 * @param clientName   name the client gives itself; self-asserted, so the consent page also shows the host
 * @param redirectUris allowed redirect URIs
 */
public record ClientMetadata(String clientId, String clientName, List<String> redirectUris) {

    public ClientMetadata {
        redirectUris = List.copyOf(redirectUris);
    }

    /**
     * Host of the {@code client_id}, the identity of the client the user can rely on.
     */
    public String host() {
        return URI.create(clientId).getHost();
    }

    /**
     * Native clients only have loopback or private-use scheme redirect URIs. Any program on the user's computer can
     * receive their authorization codes, see Docs/authentication.md.
     */
    public boolean isNative() {
        return redirectUris.stream().map(URI::create).noneMatch(uri -> "https".equalsIgnoreCase(uri.getScheme()));
    }
}
