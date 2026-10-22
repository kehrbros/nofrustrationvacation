package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.OffsetDateTime;

@Getter
@Entity
@Audited
public class Agreement extends AbstractEntity {
    @ManyToOne
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private ConsumerOrder consumerOrder;
    @ManyToOne
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private ProviderOffer providerOffer;

    private OffsetDateTime agreedAt;
    private OffsetDateTime validUntil;

    @Column(precision = 19, scale = Money.SCALE)
    private Money agreedPricePerMillionInputTokens;
    @Column(precision = 19, scale = Money.SCALE)
    private Money agreedPricePerMillionOutputTokens;

    /** Set once the consumer left the agreement unused for too long; the offer went back into the market. */
    private OffsetDateTime lastErrorAt;
    /** Last call through the agreement. Not audited, it changes on every call. */
    @NotAudited
    private OffsetDateTime lastSuccessAt;

    public Agreement(ConsumerOrder consumerOrder, ProviderOffer providerOffer, OffsetDateTime agreedAt,
                     OffsetDateTime validUntil, Money agreedPricePerMillionInputTokens,
                     Money agreedPricePerMillionOutputTokens) {
        this.consumerOrder = consumerOrder;
        this.providerOffer = providerOffer;
        this.agreedAt = agreedAt;
        this.validUntil = validUntil;
        this.agreedPricePerMillionInputTokens = agreedPricePerMillionInputTokens;
        this.agreedPricePerMillionOutputTokens = agreedPricePerMillionOutputTokens;
    }

    protected Agreement() {
    }

    /** Neither used nor agreed since {@code cutoff}. */
    public boolean isIdleSince(OffsetDateTime cutoff) {
        OffsetDateTime lastActivity = lastSuccessAt != null ? lastSuccessAt : agreedAt;
        return lastActivity != null && lastActivity.isBefore(cutoff);
    }

    public void markError(OffsetDateTime at) {
        this.lastErrorAt = at;
    }
}