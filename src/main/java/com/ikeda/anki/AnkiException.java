package com.ikeda.anki;

public class AnkiException extends RuntimeException {
    public AnkiException(String message) {
        super(message);
    }

    public AnkiException(String message, Throwable cause) {
        super(message, cause);
    }
}
