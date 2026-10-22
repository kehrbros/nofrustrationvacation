package com.vacantagent.accounting.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;

/**
 * Account a user receives payouts on, confirmed by the user at the payment provider (PayPal). A provider needs one
 * before placing offers. Audited, so linking, relinking and unlinking stay traceable.
 */
@Getter
@Entity
@Audited
public class PayoutAccount extends AbstractEntity {
    public static final String PAYPAL = "PAYPAL";

    @Column(nullable = false, unique = true)
    private long appUserId;
    @Column(nullable = false, length = 16)
    private String provider;
    /** PayPal payer id, receiver of payouts. */
    @Column(nullable = false)
    private String externalAccountId;
    /** For display only, payouts go to {@link #externalAccountId}. */
    private @Nullable String email;
    private boolean verified;
    @Column(nullable = false)
    private OffsetDateTime linkedAt;

    protected PayoutAccount() {
    }

    public PayoutAccount(long appUserId, String provider, String externalAccountId, @Nullable String email,
                         boolean verified, OffsetDateTime linkedAt) {
        this.appUserId = appUserId;
        this.provider = provider;
        relink(externalAccountId, email, verified, linkedAt);
    }

    public void relink(String externalAccountId, @Nullable String email, boolean verified, OffsetDateTime linkedAt) {
        this.externalAccountId = externalAccountId;
        this.email = email;
        this.verified = verified;
        this.linkedAt = linkedAt;
    }
}
