package com.vacantagent.accounting.logic;

import com.vacantagent.common.money.OriginalAmount;
import com.vacantagent.paypal.PayPalAmount;

import java.math.RoundingMode;
import java.util.Currency;

/** Maps booked amounts to PayPal amounts, which carry exactly the decimal places of their currency. */
final class PayPalAmounts {
    private PayPalAmounts() {
    }

    static PayPalAmount of(OriginalAmount amount) {
        int digits = Currency.getInstance(amount.currency()).getDefaultFractionDigits();
        return new PayPalAmount(amount.currency(), amount.amount().setScale(digits, RoundingMode.UNNECESSARY));
    }

    static boolean matches(OriginalAmount expected, PayPalAmount actual) {
        return expected.currency().equals(actual.currency()) && expected.amount().compareTo(actual.value()) == 0;
    }
}
