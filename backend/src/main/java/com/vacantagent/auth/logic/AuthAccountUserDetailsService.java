package com.vacantagent.auth.logic;

import com.vacantagent.auth.data.AuthAccountRepository;
import com.vacantagent.auth.entities.AuthAccount;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Used by the login form of the IdP.
 */
@Service
@RequiredArgsConstructor
public class AuthAccountUserDetailsService implements UserDetailsService {
    private final AuthAccountRepository accountRepository;

    @NullMarked
    @Override
    public UserDetails loadUserByUsername(String username) {
        AuthAccount account = accountRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .disabled(!account.isEnabled())
                .roles(account.getRoles().toArray(String[]::new))
                .build();
    }
}
