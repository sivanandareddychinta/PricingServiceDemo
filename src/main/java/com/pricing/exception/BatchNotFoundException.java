package com.pricing.exception;

/**
 * Exception thrown when attempting to operate on a non-existent batch.
 */
public class BatchNotFoundException extends RuntimeException {

    public BatchNotFoundException(String message) {
        super(message);
    }
}
