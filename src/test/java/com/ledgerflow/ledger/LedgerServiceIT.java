package com.ledgerflow.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.common.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Ledger correctness against real PostgreSQL.
 */
class LedgerServiceIT extends AbstractIntegrationTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PostingRepository postings;

    private Account fundedAccount(String number, long balance) {
        Account a = new Account(number, "owner-" + number, "USD");
        a.setBalanceMinorUnits(balance);
        return accounts.save(a);
    }

    @Test
    void transferMovesMoneyAndBalances() {
        Account a = fundedAccount("LS-A", 10_000);
        Account b = fundedAccount("LS-B", 0);

        LedgerTransaction tx = ledger.postTransfer(
                a.getId(), b.getId(), Money.of(2_500, "USD"), "ls-key-1", "test transfer");

        assertEquals(TransactionStatus.POSTED, tx.getStatus());
        assertEquals(2, tx.getPostings().size());

        Account reloaded_a = accounts.findById(a.getId()).orElseThrow();
        Account reloaded_b = accounts.findById(b.getId()).orElseThrow();
        assertEquals(7_500, reloaded_a.getBalanceMinorUnits());
        assertEquals(2_500, reloaded_b.getBalanceMinorUnits());

        // Authoritative balances derived from postings agree with the cache.
        assertEquals(7_500, postings.authoritativeBalance(a.getId(), "USD"));
        assertEquals(2_500, postings.authoritativeBalance(b.getId(), "USD"));
    }

    @Test
    void insufficientFundsRejected() {
        Account a = fundedAccount("LS-C", 100);
        Account b = fundedAccount("LS-D", 0);
        assertThrows(InsufficientFundsException.class, () ->
                ledger.postTransfer(a.getId(), b.getId(), Money.of(10_000, "USD"), "ls-key-2", "x"));
        // Nothing persisted: balances untouched.
        assertEquals(100, accounts.findById(a.getId()).orElseThrow().getBalanceMinorUnits());
    }

    @Test
    void frozenAccountRejected() {
        Account a = fundedAccount("LS-E", 10_000);
        Account b = fundedAccount("LS-F", 0);
        a.setStatus(AccountStatus.FROZEN);
        accounts.save(a);
        assertThrows(AccountNotUsableException.class, () ->
                ledger.postTransfer(a.getId(), b.getId(), Money.of(100, "USD"), "ls-key-3", "x"));
    }

    @Test
    void globalInvariantHoldsAfterManyTransfers() {
        Account a = fundedAccount("LS-G", 1_000_000);
        Account b = fundedAccount("LS-H", 0);
        for (int i = 0; i < 25; i++) {
            ledger.postTransfer(a.getId(), b.getId(), Money.of(1_000, "USD"), "ls-batch-" + i, "batch");
        }
        // Net across ALL postings must be zero per currency: money moved, never created.
        var nets = postings.netPerCurrency();
        assertTrue(nets.stream().allMatch(row -> ((Number) row[1]).longValue() == 0L),
                "global debits != credits: " + nets);
    }

    @Test
    void reversalRestoresBalancesWithoutMutatingHistory() {
        Account a = fundedAccount("LS-I", 10_000);
        Account b = fundedAccount("LS-J", 0);
        LedgerTransaction tx = ledger.postTransfer(
                a.getId(), b.getId(), Money.of(4_000, "USD"), "ls-key-4", "reversible");

        LedgerTransaction reversal = ledger.postReversal(tx.getId(), "test reversal");

        // Balances restored...
        assertEquals(10_000, accounts.findById(a.getId()).orElseThrow().getBalanceMinorUnits());
        assertEquals(0, accounts.findById(b.getId()).orElseThrow().getBalanceMinorUnits());
        // ...original rows untouched: 2 postings on the original + 2 inverse postings.
        assertEquals(2, postings.findByTransactionId(tx.getId()).size());
        assertEquals(2, postings.findByTransactionId(reversal.getId()).size());
        assertEquals(0, postings.authoritativeBalance(a.getId(), "USD") - 10_000);
        assertEquals(0, postings.authoritativeBalance(b.getId(), "USD"));
    }
}
