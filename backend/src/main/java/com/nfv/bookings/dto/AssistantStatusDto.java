package com.nfv.bookings.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether confirmations can be read automatically (assistant configured)")
public record AssistantStatusDto(boolean extractionAvailable) {
}
