package com.vacantagent.input.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

/** Origin of {@link Event}s, e.g. a traffic information service. */
@Getter
@Entity
public class EventSource extends AbstractEntity {
    @Column(nullable = false, unique = true, length = 64)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventSourceType type;
    private boolean enabled;

    protected EventSource() {
    }

    public EventSource(String name, EventSourceType type) {
        this.name = name;
        this.type = type;
        this.enabled = true;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
