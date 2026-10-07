package com.ledgerflow.idempotency;

/** A request with the same key is currently being processed. Maps to HTTP 409. */
public class ConcurrentRequestException extends RuntimeException {
    public ConcurrentRequestException(String key) {
        super("a request with idempotency key is already in progress: " + key);
    }
}
