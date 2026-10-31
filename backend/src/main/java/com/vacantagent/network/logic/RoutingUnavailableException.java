package com.vacantagent.network.logic;

import com.vacantagent.common.errorhandling.TechnicalException;

/** No graph to route on yet, e.g. the import is still running. */
public class RoutingUnavailableException extends TechnicalException {
    public RoutingUnavailableException(String message) {
        super(message);
    }
}