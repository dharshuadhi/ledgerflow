package com.ledgerflow.reconciliation;

public enum MismatchType {
    MISSING_SETTLEMENT,
    DUPLICATE_SETTLEMENT,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    UNEXPECTED_SETTLEMENT,
    LATE_SETTLEMENT
}
