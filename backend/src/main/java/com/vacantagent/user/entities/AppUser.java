package com.vacantagent.user.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table
public class AppUser extends AbstractEntity {
    private String name;
    private String mail;
    private BigDecimal accountBalance;
}
