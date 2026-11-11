package com.nfv.trips.logic;

import com.nfv.common.errorhandling.BusinessException;
import jakarta.annotation.Nullable;
import lombok.Getter;

/** Input for a trip, stop or title photo violates a rule, e.g. trip ends before it starts. */
@Getter
public class InvalidTripInputException extends BusinessException {
    /** The field at fault where the frontend marks it, e.g. {@code email} of the guest; null otherwise. */
    private final @Nullable String field;

    public InvalidTripInputException(String message) {
        this(null, message);
    }

    public InvalidTripInputException(@Nullable String field, String message) {
        super(message);
        this.field = field;
    }
}
