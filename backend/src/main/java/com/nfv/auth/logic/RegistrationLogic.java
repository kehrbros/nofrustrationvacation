package com.nfv.auth.logic;

import com.nfv.auth.data.AuthAccountRepository;
import com.nfv.auth.entities.AuthAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Self-registration of new accounts. Creates the login account only; the application side ({@code AppUser})
 * is created by the resource server on the first authenticated request.
 */
@Service
@RequiredArgsConstructor
public class RegistrationLogic {
    private final AuthAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * @throws UsernameTakenException if the username is already registered
     */
    public void register(String username, String password, String email) {
        if (accountRepository.existsByUsername(username)) {
            throw new UsernameTakenException();
        }
        try {
            accountRepository.saveAndFlush(new AuthAccount(username, passwordEncoder.encode(password), email));
        } catch (DataIntegrityViolationException e) {
            // concurrent registration with the same username, rejected by the unique constraint
            throw new UsernameTakenException();
        }
    }

    public static class UsernameTakenException extends RuntimeException {
    }
}
