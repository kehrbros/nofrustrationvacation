package com.vacantagent.user.security;

import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps a validated JWT to its {@link AppUser} by username and creates the AppUser on the first login.
 * Only standard claims are used, the claim names are configurable to support different identity providers.
 */
@Component
public class AppUserJwtAuthenticationConverter implements Converter<Jwt, AppUserAuthentication> {
    private final AppUserProvisioning appUserProvisioning;
    private final String usernameClaim;
    private final List<String> rolesClaimPath;

    public AppUserJwtAuthenticationConverter(AppUserProvisioning appUserProvisioning,
                                             @Value("${vacantagent.security.username-claim}") String usernameClaim,
                                             @Value("${vacantagent.security.roles-claim}") String rolesClaim) {
        this.appUserProvisioning = appUserProvisioning;
        this.usernameClaim = usernameClaim;
        this.rolesClaimPath = List.of(rolesClaim.split("\\."));
    }

    @Override
    public AppUserAuthentication convert(Jwt jwt) {
        String username = jwt.getClaimAsString(usernameClaim);
        if (username == null || username.isBlank()) {
            throw new InvalidBearerTokenException("Token has no claim '" + usernameClaim + "'");
        }
        AppUser appUser = appUserProvisioning.findOrCreate(username, jwt.getClaimAsString("name"), jwt.getClaimAsString("email"));
        return new AppUserAuthentication(new CurrentUser(appUser.getId(), appUser.getUsername()), jwt, roles(jwt));
    }

    /**
     * Roles from a possibly nested claim, e.g. {@code roles} (own IdP) or {@code realm_access.roles} (Keycloak).
     */
    private Collection<GrantedAuthority> roles(Jwt jwt) {
        Object value = jwt.getClaims();
        for (String key : rolesClaimPath) {
            value = value instanceof Map<?, ?> map ? map.get(key) : null;
        }
        if (!(value instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
