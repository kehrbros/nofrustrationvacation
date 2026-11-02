package com.nfv.user.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.util.Collection;

/**
 * The MCP endpoint as OAuth protected resource (MCP authorization spec): tokens must be issued for it ({@code aud} is
 * {@code nfv.mcp.resource-uri}), scopes are checked per tool by {@link McpScopeFilter}. Tokens for the MCP
 * endpoint are not accepted by the API, see {@link #rejectMcpTokens}.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpSecurityConfig {

    @Bean
    @Order(3)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, AppUserJwtAuthenticationConverter jwtConverter,
                                                      McpProperties mcp,
                                                      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        McpBearerErrors errors = new McpBearerErrors(mcp.metadataUrl());
        http.securityMatcher("/mcp", "/mcp/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(decoder(issuer, mcp.resourceUri())).jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .addFilterAfter(new McpScopeFilter(mcp.readTools(), errors), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Picked up by Spring Boot for the decoder of the API ({@code /api/**}).
     */
    @Bean
    public OAuth2TokenValidator<Jwt> rejectMcpTokens(McpProperties mcp) {
        return new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                audience -> audience == null || !audience.contains(mcp.resourceUri()));
    }

    private static JwtDecoder decoder(String issuer, String resourceUri) {
        // created on first use like Spring Boot's decoder, the issuer (own IdP) is not up yet during startup
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                    new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                            audience -> audience != null && audience.contains(resourceUri))));
            return decoder;
        });
    }
}
