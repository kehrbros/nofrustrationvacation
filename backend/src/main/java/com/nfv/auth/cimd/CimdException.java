package com.nfv.auth.cimd;

/**
 * A Client ID Metadata Document could not be fetched or is invalid. The message is logged, never shown to clients.
 */
public class CimdException extends RuntimeException {

    public CimdException(String message) {
        super(message);
    }

    public CimdException(String message, Throwable cause) {
        super(message, cause);
    }
}
