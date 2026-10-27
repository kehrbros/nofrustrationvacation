package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import org.hibernate.envers.Audited;
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

    /** Cost of a call at the agreed prices, input and output tokens priced separately. */
    public Money costFor(long promptTokens, long completionTokens) {
        return agreedPricePerMillionInputTokens.costFor(promptTokens)
                .plus(agreedPricePerMillionOutputTokens.costFor(completionTokens));
    }

    /**
     * Neither used nor agreed since {@code cutoff}; then calls are refused and the offer is free again.
     * Once idle, no call can be recorded anymore, so the moment an agreement becomes idle never moves.
     */
    public boolean isIdleSince(OffsetDateTime cutoff) {
        OffsetDateTime lastSuccessAt = consumerOrder.getLastSuccessAt();
        OffsetDateTime lastActivity = lastSuccessAt != null ? lastSuccessAt : agreedAt;
        return lastActivity != null && lastActivity.isBefore(cutoff);
    }
}