package com.nfv.user.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class CryptoConfig {

    /*@Bean
    public SecretKey masterKey(@Value("${app_master_key}") String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return new SecretKeySpec(bytes, "AES");
    }*/
}