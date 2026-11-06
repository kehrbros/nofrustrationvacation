package com.nfv.advisor.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The request for a run does not fit the route. */
public class InvalidProposalInputException extends BusinessException {
    public InvalidProposalInputException(String message) {
        super(message);
    }
}
