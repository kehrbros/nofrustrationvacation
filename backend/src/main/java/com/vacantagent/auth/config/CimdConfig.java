package com.vacantagent.auth.config;

import com.vacantagent.auth.cimd.CimdClientResolver;
import com.vacantagent.auth.cimd.CimdClientStore;
import com.vacantagent.auth.cimd.CimdProperties;
import com.vacantagent.auth.cimd.ClientMetadataSource;
import com.vacantagent.auth.cimd.ClientRegistry;
import com.vacantagent.auth.cimd.HttpsClientMetadataSource;
import com.vacantagent.auth.logic.ConnectionLogic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Clock;

/**
 * MCP clients identified by Client ID Metadata Documents and the persistence of authorizations, see
 * Docs/authentication.md.
 */
@Configuration
@EnableConfigurationProperties(CimdProperties.class)
public class CimdConfig {

    @Bean
    public CimdClientStore cimdClientStore(JdbcTemplate jdbcTemplate) {
        return new CimdClientStore(jdbcTemplate);
    }

    @Bean
    public ClientMetadataSource clientMetadataSource(CimdProperties properties) {
        return new HttpsClientMetadataSource(properties.fetchTimeout(), properties.maxDocumentSize());
    }

    @Bean
    public CimdClientResolver cimdClientResolver(CimdProperties properties, ClientMetadataSource source,
                                                 CimdClientStore store) {
        return new CimdClientResolver(properties, source, store, Clock.systemUTC());
    }

    /**
     * {@code preRegistered} is the repository Spring Boot creates from
     * {@code spring.security.oauth2.authorizationserver.client.*}.
     */
    @Bean
    public ClientRegistry clientRegistry(RegisteredClientRepository preRegistered, CimdClientResolver resolver) {
        return new ClientRegistry(preRegistered, resolver);
    }

    /**
     * Authorizations (codes, access and refresh tokens) in the database, so refresh tokens survive restarts.
     */
    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate, ClientRegistry clientRegistry) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, clientRegistry.repository());
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate,
                                                                         ClientRegistry clientRegistry) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, clientRegistry.repository());
    }

    @Bean
    public ConnectionLogic connectionLogic(JdbcTemplate jdbcTemplate, ClientRegistry clientRegistry,
                                           OAuth2AuthorizationConsentService consentService) {
        return new ConnectionLogic(jdbcTemplate, clientRegistry, consentService);
    }
}
