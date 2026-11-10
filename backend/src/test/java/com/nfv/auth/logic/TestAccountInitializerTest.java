package com.nfv.auth.logic;

import com.nfv.auth.data.AuthAccountRepository;
import com.nfv.auth.entities.AuthAccount;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An existing test account gets the role tester, a later registered account of that name does not. */
class TestAccountInitializerTest {
    private final AuthAccountRepository repository = mock(AuthAccountRepository.class);
    private final TestAccountInitializer initializer =
            new TestAccountInitializer(repository, NoOpPasswordEncoder.getInstance());

    @Test
    void seededTestAccountWithoutRoleGetsTesterRole() {
        AuthAccount seeded = new AuthAccount("test", "test", null);
        when(repository.count()).thenReturn(3L);
        when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(seeded));

        initializer.run(new DefaultApplicationArguments());

        assertThat(seeded.getRoles()).contains("user", "tester");
        verify(repository, never()).save(any());
    }

    @Test
    void testAccountThatIsNotTheOldestIsNotPromoted() {
        AuthAccount oldest = new AuthAccount("alice", "secret", null);
        when(repository.count()).thenReturn(3L);
        when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(oldest));

        initializer.run(new DefaultApplicationArguments());

        assertThat(oldest.getRoles()).containsExactly("user");
    }
}
