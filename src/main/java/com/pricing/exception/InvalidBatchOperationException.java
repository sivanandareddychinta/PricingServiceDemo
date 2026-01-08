package com.pricing.exception;

/**
 * Exception thrown when batch operations are called in incorrect order or state.
 *
 * Design Decision: Custom exception provides clear error messages for resilience
 * against incorrect producer behavior as required in specifications.
 */
public class InvalidBatchOperationException extends RuntimeException {

    public InvalidBatchOperationException(String message) {
        super(message);
    }

    public InvalidBatchOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
