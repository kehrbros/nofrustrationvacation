package com.vacantagent.auth.cimd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMetadataValidatorTest {
    private static final String CLIENT_ID = "https://client.example/oauth/metadata.json";

    @Test
    void acceptsPublicClient() {
        ClientMetadata metadata = ClientMetadataValidator.parse(CLIENT_ID, document(
                "\"client_name\":\"Example\",\"redirect_uris\":[\"https://client.example/cb\",\"http://127.0.0.1/cb\"],"
                        + "\"grant_types\":[\"authorization_code\",\"refresh_token\"],\"response_types\":[\"code\"],"
                        + "\"token_endpoint_auth_method\":\"none\""));
        assertEquals("Example", metadata.clientName());
        assertEquals(List.of("https://client.example/cb", "http://127.0.0.1/cb"), metadata.redirectUris());
        assertFalse(metadata.isNative());
    }

    @Test
    void clientWithoutHttpsRedirectIsNative() {
        ClientMetadata metadata = ClientMetadataValidator.parse(CLIENT_ID, document(
                "\"client_name\":\"CLI\",\"redirect_uris\":[\"http://localhost/cb\",\"example.client.app:/cb\"]"));
        assertTrue(metadata.isNative());
    }

    @Test
    void removesControlCharactersFromName() {
        ClientMetadata metadata = ClientMetadataValidator.parse(CLIENT_ID, document(
                "\"client_name\":\"Ex\\u202Eample\\n\",\"redirect_uris\":[\"https://client.example/cb\"]"));
        assertEquals("Example", metadata.clientName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"client_id\":\"https://other.example/metadata.json\",\"client_name\":\"X\",\"redirect_uris\":[\"https://client.example/cb\"]",
            "\"client_id\":\"" + CLIENT_ID + "\",\"redirect_uris\":[\"https://client.example/cb\"]",
            "\"client_id\":\"" + CLIENT_ID + "\",\"client_name\":\"X\",\"redirect_uris\":[]",
            "\"client_id\":\"" + CLIENT_ID + "\",\"client_name\":\"X\",\"redirect_uris\":[\"http://client.example/cb\"]",
            "\"client_id\":\"" + CLIENT_ID + "\",\"client_name\":\"X\",\"redirect_uris\":[\"com.other.app:/cb\"]",
            "\"client_id\":\"" + CLIENT_ID + "\",\"client_name\":\"X\",\"redirect_uris\":[\"https://client.example/cb#x\"]",
            "\"client_id\":\"" + CLIENT_ID + "\",\"client_name\":\"X\",\"redirect_uris\":[\"https://client.example/cb\"],"
                    + "\"token_endpoint_auth_method\":\"client_secret_basic\"",
            "\"client_id\":\"" + CLIENT_ID + "\",\"client_name\":\"X\",\"redirect_uris\":[\"https://client.example/cb\"],"
                    + "\"grant_types\":[\"client_credentials\"]",
            "\"client_id\":\"" + CLIENT_ID + "\",\"client_name\":\"X\",\"redirect_uris\":[\"https://client.example/cb\"],"
                    + "\"response_types\":[\"token\"]",
            "\"client_id\":\"" + CLIENT_ID + "\",\"client_name\":\"X\",\"redirect_uris\":\"https://client.example/cb\""})
    void rejectsInvalidDocuments(String fields) {
        assertThrows(CimdException.class, () -> ClientMetadataValidator.parse(CLIENT_ID, "{" + fields + "}"));
    }

    @Test
    void rejectsNonJson() {
        assertThrows(CimdException.class, () -> ClientMetadataValidator.parse(CLIENT_ID, "<html>"));
        assertThrows(CimdException.class, () -> ClientMetadataValidator.parse(CLIENT_ID, "[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://client.example/metadata.json",
            "https://client.example",
            "https://client.example/",
            "https://client.example:8443/metadata.json",
            "https://user@client.example/metadata.json",
            "https://client.example/metadata.json?x=1",
            "https://client.example/metadata.json#x",
            "https://client.example/oauth/../evil/metadata.json",
            "https://client.example/oauth/%2e%2e/metadata.json"})
    void rejectsInvalidClientIds(String clientId) {
        assertThrows(CimdException.class, () -> ClientMetadataValidator.validateClientId(clientId));
    }

    @Test
    void acceptsClientId() {
        assertEquals("client.example", ClientMetadataValidator.validateClientId(CLIENT_ID).getHost());
        assertTrue(ClientMetadataValidator.isUrl(CLIENT_ID));
        assertFalse(ClientMetadataValidator.isUrl("vacantagent-app"));
    }

    private static String document(String fields) {
        return "{\"client_id\":\"" + CLIENT_ID + "\"," + fields + "}";
    }
}
