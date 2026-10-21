package com.vacantagent.user.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

/**
 * Authentication of an API request: the validated JWT mapped to its {@link CurrentUser}.
 */
public class AppUserAuthentication extends AbstractAuthenticationToken {
    private final CurrentUser currentUser;
    private final Jwt jwt;

    public AppUserAuthentication(CurrentUser currentUser, Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.currentUser = currentUser;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public CurrentUser getPrincipal() {
        return currentUser;
    }

    @Override
    public Jwt getCredentials() {
        return jwt;
    }
}
