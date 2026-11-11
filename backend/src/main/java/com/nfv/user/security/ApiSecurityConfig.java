package com.nfv.user.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server for the API. Validates JWTs of the issuer configured in
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} (own auth module or e.g. Keycloak). The MCP endpoint
 * has its own chain, see {@link McpSecurityConfig}.
 */
@Configuration
public class ApiSecurityConfig {

    @Bean
    @Order(4)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, AppUserJwtAuthenticationConverter jwtConverter) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        // test tools, they can affect the routes of every user (Docs/traffic-feed.md#test-source-manual)
                        .requestMatchers("/api/v1/test/**").hasRole("tester")
                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }
}
