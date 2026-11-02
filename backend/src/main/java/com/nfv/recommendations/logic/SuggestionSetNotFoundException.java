package com.nfv.recommendations.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The set or option does not exist or belongs to another user, both look the same to the caller. */
public class SuggestionSetNotFoundException extends BusinessException {
    public SuggestionSetNotFoundException(long setId) {
        super("suggestion set " + setId + " not found");
    }
}