package com.nfv.input.entities;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Getter;

import java.time.OffsetDateTime;

/** Message pushed by a third party to a webhook, payload not interpreted yet. */
@Getter
@Entity
@DiscriminatorValue("WEBHOOK")
public class WebhookEvent extends Event {
    /** Whether the signature of the delivery was valid. */
    private boolean signatureVerified;

    protected WebhookEvent() {
    }

    public WebhookEvent(EventSource source, String externalId, String payload, OffsetDateTime receivedAt,
                        boolean signatureVerified) {
        super(source, externalId, payload, receivedAt);
        this.signatureVerified = signatureVerified;
    }
}
