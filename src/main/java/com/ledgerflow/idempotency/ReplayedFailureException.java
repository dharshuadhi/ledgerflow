package com.ledgerflow.idempotency;

/**
 * A previous attempt with the same idempotency key failed, and this retry is
 * replaying that failure deterministically instead of re-executing.
 * Carries the original HTTP status so the API layer responds identically.
 */
public class ReplayedFailureException extends RuntimeException {
    private final int status;

    public ReplayedFailureException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
