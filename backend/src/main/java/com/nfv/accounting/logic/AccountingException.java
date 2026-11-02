package com.nfv.accounting.logic;

import com.nfv.common.errorhandling.BusinessException;
import lombok.Getter;

/**
 * Business rule violation of the accounting. {@link #getCode()} is a stable code for the frontend.
 */
@Getter
public abstract class AccountingException extends BusinessException {
    private final String code;

    protected AccountingException(String code, String message) {
        super(message);
        this.code = code;
    }
}
