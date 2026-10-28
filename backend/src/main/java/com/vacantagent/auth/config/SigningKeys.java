package com.vacantagent.auth.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Key that signs the tokens of the IdP. Configured as PEM (PKCS#8 RSA private key, {@code openssl genpkey -algorithm
 * RSA -pkeyopt rsa_keygen_bits:3072}), so tokens and refresh tokens stay valid across restarts and replicas. Without it
 * a temporary key is generated.
 */
final class SigningKeys {
    private static final Logger LOG = LoggerFactory.getLogger(SigningKeys.class);

    private SigningKeys() {
    }

    static JWKSource<SecurityContext> jwkSource(String pem) {
        RSAKey key = pem == null || pem.isBlank() ? generate() : parse(pem);
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    static RSAKey parse(String pem) {
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return withThumbprint(new RSAKey.Builder(publicKey).privateKey(privateKey));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("vacantagent.auth.signing-key is not a PEM encoded PKCS#8 RSA private key", e);
        }
    }

    private static RSAKey generate() {
        LOG.warn("No signing key configured (vacantagent.auth.signing-key), using a temporary one: "
                + "tokens become invalid on restart, see Docs/deployment.md");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return withThumbprint(new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).privateKey(keyPair.getPrivate()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey withThumbprint(RSAKey.Builder builder) {
        try {
            return builder.keyIDFromThumbprint().build();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
