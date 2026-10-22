package com.vacantagent.user.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Application side of a user account. Mapped to the JWT of incoming requests by {@link #username},
 * created on the first authenticated request, see Docs/authentication.md. Balances are kept by the accounting
 * module, see Docs/accounting.md.
 */
@Getter
@Entity
@Table
public class AppUser extends AbstractEntity {
    @Column(nullable = false, unique = true)
    private String username;
    private String name;
    private String mail;
    /**
     * ISO 4217 code of the currency API responses are converted to, if no other currency is requested.
     * {@code null} means base currency, see Docs/money.md.
     */
    @Column(length = 3)
    private String preferredCurrency;

    protected AppUser() {
    }

    public static AppUser firstLogin(String username, String name, String mail) {
        AppUser user = new AppUser();
        user.username = username;
        user.name = name;
        user.mail = mail;
        return user;
    }

    public void changePreferredCurrency(String currencyCode) {
        this.preferredCurrency = currencyCode;
    }
}
