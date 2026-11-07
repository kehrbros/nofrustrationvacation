package com.nfv.advisor.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The proposal cannot be taken (any more): the run is not finished, or the trip no longer allows it. */
public class ProposalNotApplicableException extends BusinessException {
    public ProposalNotApplicableException(String message) {
        super(message);
    }
}
