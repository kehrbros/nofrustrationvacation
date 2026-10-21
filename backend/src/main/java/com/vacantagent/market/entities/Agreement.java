package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.math.BigDecimal;
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

    @Column(scale = 6)
    private BigDecimal agreedPricePerMillionInputTokens; // TODO PKE change to monetary value
    @Column(scale = 6)
    private BigDecimal agreedPricePerMillionOutputTokens; // TODO PKE change to monetary value

    protected Agreement() {
    }
}