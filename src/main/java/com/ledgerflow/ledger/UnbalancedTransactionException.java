package com.ledgerflow.ledger;

/** Thrown when a transaction would break the double-entry invariant. Never persisted. */
public class UnbalancedTransactionException extends RuntimeException {
    public UnbalancedTransactionException(String message) {
        super(message);
    }
}
