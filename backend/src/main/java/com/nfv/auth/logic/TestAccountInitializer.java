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
 * Creates the account "test" with password "test" and the role {@code tester} (test endpoints, see
 * Docs/traffic-feed.md#test-source-manual) on startup if no account exists yet. Only the login account is created; the
 * {@code AppUser} follows on the first authenticated request like for any other account.
 * <p>
 * An existing account "test" created before the role existed gets the role on startup too, but only if it is the oldest
 * account (lowest id): that is the account this initializer created on the empty database. Anyone can register, so the
 * name alone proves nothing; a later registered "test" is never promoted.
 * Disable with {@code nfv.auth.test-account.enabled=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nfv.auth.test-account.enabled", havingValue = "true")
public class TestAccountInitializer implements ApplicationRunner {
    static final String USERNAME = "test";
    static final String PASSWORD = "test";
    static final String TESTER_ROLE = "tester";

    private final AuthAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        if (accountRepository.count() > 0) {
            grantTesterRoleToSeededAccount();
            return;
        }
        AuthAccount account = new AuthAccount(USERNAME, passwordEncoder.encode(PASSWORD), null);
        account.grant(TESTER_ROLE);
        accountRepository.save(account);
        log.info("No account found, created test account '{}'.", USERNAME);
    }

    private void grantTesterRoleToSeededAccount() {
        accountRepository.findFirstByOrderByIdAsc()
                .filter(account -> USERNAME.equals(account.getUsername()))
                .filter(account -> !account.getRoles().contains(TESTER_ROLE))
                .ifPresent(account -> {
                    account.grant(TESTER_ROLE);
                    log.info("Granted role '{}' to the existing test account '{}'.", TESTER_ROLE, USERNAME);
                });
    }
}
