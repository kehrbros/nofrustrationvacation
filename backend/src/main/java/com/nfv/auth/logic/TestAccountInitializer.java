package com.nfv.auth.logic;

import com.nfv.auth.data.AuthAccountRepository;
import com.nfv.auth.entities.AuthAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the account "test" with password "test" on startup if no account exists yet. Only the login account
 * is created; the {@code AppUser} follows on the first authenticated request like for any other account.
 * Disable with {@code nfv.auth.test-account.enabled=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nfv.auth.test-account.enabled", havingValue = "true")
public class TestAccountInitializer implements ApplicationRunner {
    static final String USERNAME = "test";
    static final String PASSWORD = "test";

    private final AuthAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        if (accountRepository.count() > 0) {
            return;
        }
        accountRepository.save(new AuthAccount(USERNAME, passwordEncoder.encode(PASSWORD), null));
        log.info("No account found, created test account '{}'.", USERNAME);
    }
}
