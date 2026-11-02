package com.nfv.user.logic;

import com.nfv.user.data.AppUserRepository;
import com.nfv.user.entities.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so that {@code REQUIRES_NEW} applies via the transaction proxy: a failed insert must not
 * leave the caller's persistence context or transaction unusable.
 */
@Component
@RequiredArgsConstructor
class AppUserCreator {
    private final AppUserRepository appUserRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AppUser create(String username, String name, String mail) {
        // flush immediately, so a unique constraint violation surfaces here and not on commit
        return appUserRepository.saveAndFlush(AppUser.firstLogin(username, name, mail));
    }
}
