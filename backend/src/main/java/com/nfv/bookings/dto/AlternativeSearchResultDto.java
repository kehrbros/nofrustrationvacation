package com.nfv.bookings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.List;

@Schema(description = "Candidates best first, compared and ranked by the assistant if ranked is true, else by price")
public record AlternativeSearchResultDto(List<AlternativeCandidateDto> candidates,
                                         @Nullable String recommendedHotelId, boolean ranked,
                                         @Schema(description = "Offers LiteAPI returned before checking the criteria")
                                         int offersChecked,
                                         @Schema(description = "Hotels meeting all criteria that the assistant left "
                                                 + "out as not comparable to the current accommodation")
                                         int excludedByAssistant) {
}
