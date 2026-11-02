package com.nfv.auth.config;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigningKeysTest {

    @Test
    void parsesPkcs8PemWithStableKeyId() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----\n";

        RSAKey key = SigningKeys.parse(pem);
        assertTrue(key.isPrivate());
        assertEquals(key.getKeyID(), SigningKeys.parse(pem).getKeyID());
    }

    @Test
    void rejectsInvalidKey() {
        assertThrows(IllegalStateException.class, () -> SigningKeys.parse("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"));
    }
}
