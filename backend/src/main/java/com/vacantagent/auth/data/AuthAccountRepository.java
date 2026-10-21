package com.vacantagent.auth.data;

import com.vacantagent.auth.entities.AuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthAccountRepository extends JpaRepository<AuthAccount, Long> {
    Optional<AuthAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
