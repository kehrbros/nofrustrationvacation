package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;
import jakarta.annotation.Nullable;
import lombok.Getter;

/** Input of a booking or reservation violates a rule. {@link #field} names the guest field at fault, if it is one. */
@Getter
public class InvalidBookingInputException extends BusinessException {
    /** {@code firstName}, {@code lastName}, {@code email} or {@code phone}; null if the input as a whole is wrong. */
    private final @Nullable String field;

    public InvalidBookingInputException(String message) {
        this(null, message);
    }

    public InvalidBookingInputException(@Nullable String field, String message) {
        super(message);
        this.field = field;
    }
}
