package com.ledgerflow.ledger;

/** Kind of business event a ledger transaction represents. */
public enum TransactionType {
    TRANSFER,
    FX_CONVERSION,
    FEE,
    ADJUSTMENT
}
