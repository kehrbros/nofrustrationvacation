package com.nfv.auth.api;

import com.nfv.auth.cimd.ClientRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Consent page of the IdP for clients with {@code require-authorization-consent} (all MCP clients). The form is posted
 * to {@code /oauth2/authorize}, the user can deselect scopes.
 */
@Controller
@RequiredArgsConstructor
public class ConsentController {
    private static final OAuth2TokenType STATE = new OAuth2TokenType(OAuth2ParameterNames.STATE);

    private final ClientRegistry clientRegistry;
    private final OAuth2AuthorizationService authorizationService;

    @GetMapping("/oauth2/consent")
    public String consent(@RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
                          @RequestParam(OAuth2ParameterNames.STATE) String state,
                          @RequestParam(name = OAuth2ParameterNames.SCOPE, defaultValue = "") String scope,
                          Authentication user, Model model) {
        RegisteredClient client = clientRegistry.findByClientId(clientId);
        OAuth2Authorization pending = authorizationService.findByToken(state, STATE);
        if (client == null || pending == null || !pending.getRegisteredClientId().equals(client.getId())
                || !pending.getPrincipalName().equals(user.getName())) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown or expired authorization request");
        }
        OAuth2AuthorizationRequest request = pending.getAttribute(OAuth2AuthorizationRequest.class.getName());
        URI redirectUri = URI.create(request.getRedirectUri() != null ? request.getRedirectUri()
                : client.getRedirectUris().iterator().next());
        List<Scopes.Scope> scopes = Arrays.stream(scope.split(" "))
                .filter(value -> !value.isBlank() && client.getScopes().contains(value))
                .map(Scopes::describe)
                .toList();

        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", client.getClientName());
        model.addAttribute("clientHost", ClientRegistry.isCimd(client) ? URI.create(clientId).getHost() : null);
        boolean local = !"https".equalsIgnoreCase(redirectUri.getScheme());
        model.addAttribute("redirectHost", local ? null : redirectUri.getHost());
        model.addAttribute("local", local);
        model.addAttribute("readOnly", local && ClientRegistry.isCimd(client));
        model.addAttribute("state", state);
        model.addAttribute("scopes", scopes);
        model.addAttribute("username", user.getName());
        return "auth/consent";
    }
}
