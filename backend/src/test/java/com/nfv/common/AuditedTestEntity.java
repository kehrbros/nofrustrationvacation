package com.nfv.common;

import com.nfv.common.data.AbstractEntity;
import jakarta.persistence.Entity;
import org.hibernate.envers.Audited;

@Entity
@Audited
public class AuditedTestEntity extends AbstractEntity {
    private String name;

    protected AuditedTestEntity() {
    }

    AuditedTestEntity(String name) {
        this.name = name;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }
}
