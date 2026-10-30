package com.vacantagent.network.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;

/** Road of the road network, e.g. the motorway A6. Consists of {@link Segment}s, see Docs/domain.md. */
@Getter
@Entity
public class Road extends AbstractEntity {
    /** Official road number, e.g. {@code A6} or {@code B27}. */
    @Column(nullable = false, unique = true, length = 32)
    private String number;
    private String name;

    protected Road() {
    }

    public Road(String number, String name) {
        this.number = number;
        this.name = name;
    }
}
