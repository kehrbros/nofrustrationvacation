package com.vacantagent.user.logic;

import com.vacantagent.user.data.AppUserRepository;
import com.vacantagent.user.entities.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Resolves the {@link AppUser} of an authenticated user and creates it on the first login.
 * <p>
 * Parallel first requests of the same user are resolved by the unique constraint on {@code app_user.username}
 * instead of locks: a row that does not exist yet cannot be locked. The losing insert fails and re-reads the
 * row created by the winner.
 */
@Service
@RequiredArgsConstructor
public class AppUserProvisioning {
    private final AppUserRepository appUserRepository;
    private final AppUserCreator appUserCreator;

    public AppUser findOrCreate(String username, String name, String mail) {
        return appUserRepository.findByUsername(username)
                .orElseGet(() -> createOrReload(username, name, mail));
    }

    private AppUser createOrReload(String username, String name, String mail) {
        try {
            return appUserCreator.create(username, name, mail);
        } catch (DataIntegrityViolationException alreadyCreated) {
            return appUserRepository.findByUsername(username).orElseThrow(() -> alreadyCreated);
        }
    }
}
