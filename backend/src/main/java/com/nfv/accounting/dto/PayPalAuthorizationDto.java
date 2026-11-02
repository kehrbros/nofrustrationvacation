package com.nfv.accounting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** PayPal login page to send the user to, PayPal returns to the configured redirect URI with code and state. */
public record PayPalAuthorizationDto(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String authorizationUrl) {
}
