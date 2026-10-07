package com.ledgerflow.idempotency;

/** The key was already used with a different request body. Maps to HTTP 422. */
public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String key) {
        super("idempotency key was already used with a different request: " + key);
    }
}
