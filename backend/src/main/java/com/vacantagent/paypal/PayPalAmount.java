package com.vacantagent.paypal;

import java.math.BigDecimal;

/**
 * Amount as PayPal handles it: ISO 4217 code and a value with the decimal places of the currency.
 */
public record PayPalAmount(String currency, BigDecimal value) {
    String formattedValue() {
        return value.toPlainString();
    }
}
