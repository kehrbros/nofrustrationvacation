package com.nfv.recommendations.logic;

import com.nfv.common.errorhandling.BusinessException;

/** Request for suggestions with invalid values, e.g. more than the allowed count. */
public class InvalidSuggestionInputException extends BusinessException {
    public InvalidSuggestionInputException(String message) {
        super(message);
    }
}