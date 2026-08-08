package com.ikeda.anki;

/** Raised when the local Anki collection cannot be reached or read. */
public class AnkiException extends RuntimeException {

    public AnkiException(String message) {
        super(message);
    }

    public AnkiException(String message, Throwable cause) {
        super(message, cause);
    }
}
