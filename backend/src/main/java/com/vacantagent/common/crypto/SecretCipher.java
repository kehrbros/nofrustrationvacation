package com.vacantagent.common.crypto;

import com.vacantagent.common.errorhandling.TechnicalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets of users (e.g. API keys of agents) for storage with AES-256-GCM. The key comes from
 * {@code VACANTAGENT_ENCRYPTION_KEY} (32 random bytes, Base64), see Docs/agents.md. Without it a random key is
 * generated at startup: fine for the in-memory development database, stored secrets are unreadable after a restart.
 */
@Slf4j
@Component
public class SecretCipher {
    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public SecretCipher(@Value("${vacantagent.secrets.encryption-key:}") String base64Key) {
        byte[] keyBytes;
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("No vacantagent.secrets.encryption-key (VACANTAGENT_ENCRYPTION_KEY) set, using a temporary key. "
                    + "Stored secrets are unreadable after a restart.");
            keyBytes = new byte[32];
            RANDOM.nextBytes(keyBytes);
        } else {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
            if (keyBytes.length != 32) {
                throw new IllegalStateException("vacantagent.secrets.encryption-key must be 32 bytes, Base64 encoded");
            }
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** @return {@code v1:} + Base64(IV + ciphertext + tag), a new random IV each time */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new TechnicalException("Encryption failed: " + e.getMessage());
        }
    }

    /** @throws TechnicalException if the value was encrypted with another key or was changed */
    public String decrypt(String stored) {
        if (!stored.startsWith(VERSION)) {
            throw new TechnicalException("Unknown secret format");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new TechnicalException("Secret cannot be decrypted, was the encryption key changed?");
        }
    }
}
