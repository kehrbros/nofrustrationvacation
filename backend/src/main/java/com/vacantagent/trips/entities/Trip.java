package com.vacantagent.trips.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;

/** Journey of a user along {@link Stop}s, connected by {@link Route}s, see Docs/domain.md. */
@Getter
@Entity
@Audited
public class Trip extends AbstractEntity {
    @Column(nullable = false)
    private long appUserId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected Trip() {
    }

    public Trip(long appUserId, String name, OffsetDateTime createdAt) {
        this.appUserId = appUserId;
        this.name = name;
        this.createdAt = createdAt;
    }

    public void rename(String name) {
        this.name = name;
    }
}
