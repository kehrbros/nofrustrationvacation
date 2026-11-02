package com.nfv.auth.cimd;

import java.net.URI;
import java.time.Duration;

/**
 * Fetches Client ID Metadata Documents. Replaced in tests, which cannot reach real clients.
 */
public interface ClientMetadataSource {

    /**
     * @return the document and how long it may be cached ({@code Cache-Control: max-age}, null if not given)
     * @throws CimdException if it cannot be fetched
     */
    Document fetch(URI clientId);

    record Document(String json, Duration maxAge) {
    }
}
