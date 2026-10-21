package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.OffsetDateTime;

@Getter
@Entity
@Audited
@AllArgsConstructor
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

    protected Agreement() {
    }
}