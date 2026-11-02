package com.nfv.common.data;

import org.hibernate.envers.RevisionListener;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Writes the username of the authenticated user into each {@link Revision}.
 */
public class UsernameRevisionListener implements RevisionListener {

    @Override
    public void newRevision(Object revisionEntity) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            ((Revision) revisionEntity).setUsername(authentication.getName());
        }
    }
}
