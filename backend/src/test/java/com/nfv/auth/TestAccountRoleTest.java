package com.nfv.auth;

import com.nfv.auth.data.AuthAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** The account created on an empty database may use the test endpoints. */
@SpringBootTest
class TestAccountRoleTest {
    @Autowired
    private AuthAccountRepository accountRepository;

    @Test
    void testAccountHasTheTesterRole() {
        assertThat(accountRepository.findByUsername("test")).hasValueSatisfying(
                account -> assertThat(account.getRoles()).contains("user", "tester"));
    }
}
