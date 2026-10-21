package com.vacantagent.auth.entities;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Login account of the own identity provider. Intentionally does not extend {@code common.data.AbstractEntity}:
 * the auth module has no dependencies to other modules.
 */
@Getter
@Entity
@Table(name = "auth_account")
public class AuthAccount {
    public static final String DEFAULT_ROLE = "user";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false, unique = true)
    private String username;
    @Column(nullable = false)
    private String passwordHash;
    private String email;
    private boolean enabled;
    private OffsetDateTime createdAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "auth_account_role", joinColumns = @JoinColumn(name = "account_id"))
    @Column(name = "role")
    private Set<String> roles = new HashSet<>();

    protected AuthAccount() {
    }

    public AuthAccount(String username, String passwordHash, String email) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.email = email;
        this.enabled = true;
        this.createdAt = OffsetDateTime.now();
        this.roles.add(DEFAULT_ROLE);
    }
}
