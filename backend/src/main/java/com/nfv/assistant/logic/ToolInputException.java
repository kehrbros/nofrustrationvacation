package com.nfv.assistant.logic;

/** A tool cannot work with these arguments. The model gets the message and can try again, so it must not hold secrets. */
public class ToolInputException extends RuntimeException {
    public ToolInputException(String message) {
        super(message);
    }
}
