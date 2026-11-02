package com.nfv.user.security;

import java.security.Principal;

/**
 * Principal of every authenticated API request. Inject via {@code @AuthenticationPrincipal CurrentUser user}.
 */
public record CurrentUser(long appUserId, String username) implements Principal {
    @Override
    public String getName() {
        return username;
    }
}
