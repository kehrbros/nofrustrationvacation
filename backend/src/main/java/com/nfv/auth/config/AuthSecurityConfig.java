package com.nfv.auth.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nfv.auth.cimd.CimdProperties;
import com.nfv.auth.cimd.ClientRegistry;
import com.nfv.auth.data.AuthAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2PushedAuthorizationRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Own identity provider. Must not depend on other modules of the backend, so it can be replaced by an
 * external IdP such as Keycloak, see Docs/authentication.md. Pre-registered clients and the issuer are
 * configured via {@code spring.security.oauth2.authorizationserver.*}, MCP clients via metadata documents
 * ({@link CimdConfig}).
 */
@Configuration
public class AuthSecurityConfig {

    static final String ROLE_PREFIX = "ROLE_";
    static final String CONSENT_PAGE = "/oauth2/consent";
    private static final String CLIENT_ID_METADATA_DOCUMENT_SUPPORTED = "client_id_metadata_document_supported";
    private static final String TOKEN_ENDPOINT_AUTH_METHODS_SUPPORTED = "token_endpoint_auth_methods_supported";
    private static final String SCOPES_SUPPORTED = "scopes_supported";

    /**
     * OAuth2/OIDC protocol endpoints ({@code /oauth2/**}, {@code /.well-known/**}, ...).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            @Value("${nfv.auth.cors.allowed-origins}") List<String> allowedOrigins,
            @Value("${nfv.auth.allowed-resources}") List<String> allowedResources,
            ClientRegistry clientRegistry,
            CimdProperties cimdProperties,
            OAuth2AuthorizationService authorizationService,
            OAuth2AuthorizationConsentService authorizationConsentService,
            OAuth2TokenGenerator<?> tokenGenerator) {
        RegisteredClientRepository clients = clientRegistry.repository();
        AuthorizationRequestValidator validator = new AuthorizationRequestValidator(
                allowedResources.stream().map(Resources::canonical).toList(), cimdProperties.nativeScopes());
        Consumer<Map<String, Object>> metadata = claims -> metadata(claims, cimdProperties);
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server
                        .registeredClientRepository(clients)
                        .authorizationService(authorizationService)
                        .authorizationConsentService(authorizationConsentService)
                        .tokenGenerator(tokenGenerator)
                        .authorizationEndpoint(endpoint -> endpoint
                                .consentPage(CONSENT_PAGE)
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider p) {
                                        p.setAuthenticationValidator(validator);
                                    }
                                })))
                        .pushedAuthorizationRequestEndpoint(endpoint -> endpoint
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2PushedAuthorizationRequestAuthenticationProvider p) {
                                        p.setAuthenticationValidator(validator);
                                    }
                                })))
                        // MCP clients and the frontend are public clients and refresh (and revoke) their tokens with client_id only
                        .clientAuthentication(authentication -> authentication
                                .authenticationConverter(new PublicClientRefreshAuthenticationConverter())
                                .authenticationProvider(new PublicClientRefreshAuthenticationProvider(clients)))
                        .authorizationServerMetadataEndpoint(endpoint -> endpoint
                                .authorizationServerMetadataCustomizer(builder -> builder.claims(metadata)))
                        .oidc(oidc -> oidc.providerConfigurationEndpoint(endpoint -> endpoint
                                .providerConfigurationCustomizer(builder -> builder.claims(metadata)))))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(allowedOrigins)))
                // unauthenticated browser requests to /oauth2/authorize are sent to the login page
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    /**
     * Server-rendered pages of the IdP: login, registration, consent and the connected applications of the user.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain authPagesSecurityFilterChain(HttpSecurity http,
                                                            @Value("${nfv.frontend-url}") String frontendUrl) {
        String app = frontendUrl + "/";
        http.securityMatcher("/login", "/logout", "/register", CONSENT_PAGE, "/account/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/logout", "/register").permitAll()
                        .anyRequest().authenticated())
                // without a saved authorization request (login page opened directly) the login continues in the app
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl(app))
                // outdated forms (double submit, back button, expired session) lead back to the form or into the app
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(new StaleFormAccessDeniedHandler(app)))
                .logout(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Signing key of the tokens, see {@link SigningKeys}.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(@Value("${nfv.auth.signing-key:}") String signingKey) {
        return SigningKeys.jwkSource(signingKey);
    }

    /**
     * Like Spring's default, but with refresh tokens for public clients, see {@link PublicClientRefreshTokenGenerator}.
     */
    @Bean
    public OAuth2TokenGenerator<?> tokenGenerator(JWKSource<SecurityContext> jwkSource,
                                                  OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(tokenCustomizer);
        return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2AccessTokenGenerator(),
                new PublicClientRefreshTokenGenerator());
    }

    /**
     * Adds the claims the resource server relies on. {@code sub} already is the username. The audience of access
     * tokens for a resource (MCP) is that resource, see {@link ResourceAudience}.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(AuthAccountRepository accountRepository) {
        return context -> {
            boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
            boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
            if (!accessToken && !idToken) {
                return;
            }
            ResourceAudience.apply(context);
            accountRepository.findByUsername(context.getPrincipal().getName()).ifPresent(account -> {
                context.getClaims().claim("preferred_username", account.getUsername());
                if (account.getEmail() != null) {
                    context.getClaims().claim("email", account.getEmail());
                }
            });
            if (accessToken) {
                List<String> roles = context.getPrincipal().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
                        .map(authority -> authority.substring(ROLE_PREFIX.length()))
                        // mutable list, see ResourceAudience
                        .collect(Collectors.toCollection(ArrayList::new));
                context.getClaims().claim("roles", roles);
            }
        };
    }

    /**
     * MCP clients look for public clients and metadata document support (MCP authorization spec).
     */
    private static void metadata(Map<String, Object> claims, CimdProperties cimdProperties) {
        addAll(claims, TOKEN_ENDPOINT_AUTH_METHODS_SUPPORTED, List.of(ClientAuthenticationMethod.NONE.getValue()));
        if (cimdProperties.enabled()) {
            claims.put(CLIENT_ID_METADATA_DOCUMENT_SUPPORTED, true);
            addAll(claims, SCOPES_SUPPORTED, cimdProperties.scopes());
        }
    }

    private static void addAll(Map<String, Object> claims, String name, List<String> values) {
        Set<Object> merged = new LinkedHashSet<>();
        if (claims.get(name) instanceof Collection<?> existing) {
            merged.addAll(existing);
        }
        merged.addAll(values);
        claims.put(name, List.copyOf(merged));
    }

    private static CorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST"));
        configuration.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
