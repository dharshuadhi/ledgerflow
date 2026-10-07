package com.ledgerflow.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LedgerTransactionTest {

    private Account account(String number, String currency) {
        return new Account(number, "owner-" + number, currency);
    }

    @Test
    void balancedTransactionPasses() {
        var tx = new LedgerTransaction(TransactionType.TRANSFER, "k1", "test");
        var a = account("A1", "USD");
        var b = account("B1", "USD");
        tx.addPosting(new Posting(tx, a, PostingDirection.DEBIT, 500, "USD"));
        tx.addPosting(new Posting(tx, b, PostingDirection.CREDIT, 500, "USD"));
        assertDoesNotThrow(tx::validateBalanced);
    }

    @Test
    void unbalancedTransactionFails() {
        var tx = new LedgerTransaction(TransactionType.TRANSFER, "k2", "test");
        var a = account("A2", "USD");
        var b = account("B2", "USD");
        tx.addPosting(new Posting(tx, a, PostingDirection.DEBIT, 500, "USD"));
        tx.addPosting(new Posting(tx, b, PostingDirection.CREDIT, 499, "USD"));
        assertThrows(UnbalancedTransactionException.class, tx::validateBalanced);
    }

    @Test
    void singlePostingFails() {
        var tx = new LedgerTransaction(TransactionType.TRANSFER, "k3", "test");
        tx.addPosting(new Posting(tx, account("A3", "USD"), PostingDirection.DEBIT, 500, "USD"));
        assertThrows(UnbalancedTransactionException.class, tx::validateBalanced);
    }

    @Test
    void multiCurrencyLegsMustEachBalance() {
        var tx = new LedgerTransaction(TransactionType.FX_CONVERSION, "k4", "fx");
        var a = account("A4", "USD");
        var b = account("B4", "EUR");
        // USD leg balances, EUR leg does not.
        tx.addPosting(new Posting(tx, a, PostingDirection.DEBIT, 1000, "USD"));
        tx.addPosting(new Posting(tx, a, PostingDirection.CREDIT, 1000, "USD"));
        tx.addPosting(new Posting(tx, b, PostingDirection.DEBIT, 900, "EUR"));
        tx.addPosting(new Posting(tx, b, PostingDirection.CREDIT, 800, "EUR"));
        assertThrows(UnbalancedTransactionException.class, tx::validateBalanced);
    }

    @Test
    void nonPositivePostingAmountsRejected() {
        var tx = new LedgerTransaction(TransactionType.TRANSFER, "k5", "test");
        var a = account("A5", "USD");
        assertThrows(IllegalArgumentException.class,
                () -> new Posting(tx, a, PostingDirection.DEBIT, 0, "USD"));
    }
}
