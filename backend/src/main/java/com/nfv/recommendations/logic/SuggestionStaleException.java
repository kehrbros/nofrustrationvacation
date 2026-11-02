package com.nfv.recommendations.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The set is no longer {@code CURRENT} (route or incident changed, applied, expired): ask for new suggestions. */
public class SuggestionStaleException extends BusinessException {
    public SuggestionStaleException(long setId) {
        super("suggestion set " + setId + " is no longer current");
    }
}