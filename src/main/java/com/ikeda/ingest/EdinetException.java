package com.ikeda.ingest;

public class EdinetException extends RuntimeException {
    public EdinetException(String message) {
        super(message);
    }

    public EdinetException(String message, Throwable cause) {
        super(message, cause);
    }
}
