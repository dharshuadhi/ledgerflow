package com.ledgerflow.ledger;

/**
 * Ledger transactions are immutable once posted. Corrections use a linked
 * reversal transaction, never an UPDATE — history must stay auditable.
 */
public enum TransactionStatus {
    POSTED,
    REVERSED
}
