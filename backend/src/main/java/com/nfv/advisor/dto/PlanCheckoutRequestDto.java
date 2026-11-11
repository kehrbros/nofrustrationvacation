package com.nfv.advisor.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The person the hotels of the plan are booked for")
public record PlanCheckoutRequestDto(String firstName, String lastName, String email, String phone) {
}
