package com.vacantagent.common.data;

import com.vacantagent.common.money.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;

/**
 * Persists {@link Money} as a single {@code NUMERIC(19, 8)} column without currency, see Docs/money.md.
 */
@Converter(autoApply = true)
public class MoneyConverter implements AttributeConverter<Money, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Money money) {
        return (money == null) ? null : money.amount();
    }

    @Override
    public Money convertToEntityAttribute(BigDecimal amount) {
        return (amount == null) ? null : Money.of(amount);
    }
}
