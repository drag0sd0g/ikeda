package com.ikeda.store;

/** Raised when the corpus store cannot be opened or written. */
public class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
