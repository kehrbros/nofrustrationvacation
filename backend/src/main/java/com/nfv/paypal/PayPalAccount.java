package com.nfv.paypal;

import jakarta.annotation.Nullable;

/**
 * PayPal account confirmed by its owner via "Log in with PayPal".
 *
 * @param payerId  stable account id, receiver of payouts ({@code recipient_type PAYPAL_ID})
 * @param email    primary e-mail address, for display only
 * @param verified PayPal's verification status of the account
 */
public record PayPalAccount(String payerId, @Nullable String email, boolean verified) {
}
