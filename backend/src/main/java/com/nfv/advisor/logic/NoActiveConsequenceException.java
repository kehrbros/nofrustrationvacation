package com.nfv.advisor.logic;

import com.nfv.common.errorhandling.BusinessException;

/** Nothing to look for: the route has no current {@code TOO_LATE} or {@code DELAYED} consequence. */
public class NoActiveConsequenceException extends BusinessException {
    public NoActiveConsequenceException(long routeId) {
        super("route " + routeId + " has no current consequence");
    }
}
