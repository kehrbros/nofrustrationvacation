package com.nfv.input.logic;

import com.nfv.common.errorhandling.TechnicalException;

/** The source could not be fetched or its response not be parsed. Never clears anything, the poll backs off. */
public class SourceUnavailableException extends TechnicalException {
    public SourceUnavailableException(String message) {
        super(message);
    }

    public SourceUnavailableException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
