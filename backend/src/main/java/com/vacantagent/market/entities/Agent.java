package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.user.entities.AppUser;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Serves one or more {@link ProviderOffer}s: the offered model and how to call it. */
@Getter
@Entity
@AllArgsConstructor
public class Agent extends AbstractEntity {
    private String modelProvider;
    private String modelName;

    /** Credential for calling the provider's agent. Never returned through the API. */
    private String secret;

    /** Only its owner may place offers with it. */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser appUser;

    protected Agent() {
    }
}