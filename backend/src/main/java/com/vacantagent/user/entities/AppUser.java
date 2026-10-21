package com.vacantagent.user.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Application side of a user account. Mapped to the JWT of incoming requests by {@link #username},
 * created on the first authenticated request, see Docs/authentication.md.
 */
@Getter
@Entity
@Table
public class AppUser extends AbstractEntity {
    @Column(nullable = false, unique = true)
    private String username;
    private String name;
    private String mail;
    private BigDecimal accountBalance;

    protected AppUser() {
    }

    public static AppUser firstLogin(String username, String name, String mail) {
        AppUser user = new AppUser();
        user.username = username;
        user.name = name;
        user.mail = mail;
        user.accountBalance = BigDecimal.ZERO;
        return user;
    }
}
