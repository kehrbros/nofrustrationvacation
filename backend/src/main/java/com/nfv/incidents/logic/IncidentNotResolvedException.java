package com.nfv.incidents.logic;

import com.nfv.common.errorhandling.BusinessException;

/** A hint can only be removed from the map once its incident has ended. */
public class IncidentNotResolvedException extends BusinessException {
    public IncidentNotResolvedException(long incidentId) {
        super("incident " + incidentId + " has not ended yet");
    }
}
