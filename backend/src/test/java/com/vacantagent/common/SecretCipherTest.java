package com.vacantagent.common;

import com.vacantagent.common.crypto.SecretCipher;
import com.vacantagent.common.errorhandling.TechnicalException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptedValueIsRandomizedAndDecryptsBack() {
        SecretCipher cipher = new SecretCipher(KEY);

        String first = cipher.encrypt("sk-secret-1234");
        String second = cipher.encrypt("sk-secret-1234");

        assertThat(first).startsWith("v1:").doesNotContain("sk-secret").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("sk-secret-1234");
    }

    @Test
    void otherKeyOrChangedValueCannotBeDecrypted() {
        String encrypted = new SecretCipher(KEY).encrypt("sk-secret-1234");
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;

        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(otherKey)).decrypt(encrypted))
                .isInstanceOf(TechnicalException.class);
        String changed = encrypted.substring(0, encrypted.length() - 2) + (encrypted.endsWith("A=") ? "B=" : "A=");
        assertThatThrownBy(() -> new SecretCipher(KEY).decrypt(changed)).isInstanceOf(TechnicalException.class);
    }

    @Test
    void keyMustHave32Bytes() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
