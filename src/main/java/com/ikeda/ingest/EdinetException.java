package com.ikeda.ingest;

/** Raised when the EDINET API cannot be reached or returns an unusable response. */
public class EdinetException extends RuntimeException {

    public EdinetException(String message) {
        super(message);
    }

    public EdinetException(String message, Throwable cause) {
        super(message, cause);
    }
}
