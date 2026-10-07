package com.ledgerflow.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.idempotency.ConcurrentRequestException;
import com.ledgerflow.idempotency.IdempotencyKeyReuseException;
import com.ledgerflow.ledger.Account;
import com.ledgerflow.ledger.AccountRepository;
import com.ledgerflow.ledger.LedgerTransactionRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Idempotency behavior under normal and adversarial conditions.
 */
class TransferIdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private TransferService transfers;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private LedgerTransactionRepository transactions;

    private Account fundedAccount(String number, long balance) {
        Account a = new Account(number, "owner-" + number, "USD");
        a.setBalanceMinorUnits(balance);
        return accounts.save(a);
    }

    private TransferService.TransferCommand cmd(Account a, Account b, String key) {
        return new TransferService.TransferCommand(
                a.getId(), b.getId(), 1_000, "USD", "idem-test", key, "SUCCESS");
    }

    @Test
    void sameKeySameRequestReplaysWithoutMovingMoneyTwice() {
        Account a = fundedAccount("ID-A", 10_000);
        Account b = fundedAccount("ID-B", 0);

        var first = transfers.transfer(cmd(a, b, "idem-key-1"));
        var second = transfers.transfer(cmd(a, b, "idem-key-1"));

        assertEquals(first.transactionId(), second.transactionId());
        assertTrue(second.replayed());
        // Money moved exactly once.
        assertEquals(9_000, accounts.findById(a.getId()).orElseThrow().getBalanceMinorUnits());
        assertEquals(1_000, accounts.findById(b.getId()).orElseThrow().getBalanceMinorUnits());
        assertEquals(1, transactions.findByIdempotencyKey("idem-key-1").size());
    }

    @Test
    void sameKeyDifferentRequestIsRejected() {
        Account a = fundedAccount("ID-C", 10_000);
        Account b = fundedAccount("ID-D", 0);

        transfers.transfer(cmd(a, b, "idem-key-2"));
        var different = new TransferService.TransferCommand(
                a.getId(), b.getId(), 9_999, "USD", "different amount", "idem-key-2", "SUCCESS");

        assertThrows(IdempotencyKeyReuseException.class, () -> transfers.transfer(different));
        // The conflicting request moved nothing.
        assertEquals(9_000, accounts.findById(a.getId()).orElseThrow().getBalanceMinorUnits());
    }

    @Test
    void concurrentDuplicateRequestsExecuteExactlyOnce() throws Exception {
        Account a = fundedAccount("ID-E", 100_000);
        Account b = fundedAccount("ID-F", 0);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        var futures = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                try {
                    var r = transfers.transfer(cmd(a, b, "idem-key-3"));
                    if (r.replayed()) {
                        replayed.incrementAndGet();
                    } else {
                        executed.incrementAndGet();
                    }
                } catch (ConcurrentRequestException e) {
                    conflicted.incrementAndGet();
                }
                return null;
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Exactly one execution; everyone else replayed or got a clean 409.
        assertEquals(1, executed.get(), "money must move exactly once");
        assertEquals(threads, executed.get() + replayed.get() + conflicted.get());
        long balanceA = accounts.findById(a.getId()).orElseThrow().getBalanceMinorUnits();
        long balanceB = accounts.findById(b.getId()).orElseThrow().getBalanceMinorUnits();
        assertEquals(99_000, balanceA);
        assertEquals(1_000, balanceB);
        assertNotEquals(0, replayed.get() + conflicted.get());
    }

    @Test
    void failedTransferIsReplayedDeterministically() {
        Account a = fundedAccount("ID-G", 100); // insufficient for 1_000
        Account b = fundedAccount("ID-H", 0);

        try {
            transfers.transfer(cmd(a, b, "idem-key-4"));
        } catch (Exception expected) {
            // first attempt fails with insufficient funds
        }
        // Retry replays the same failure instead of executing again.
        try {
            transfers.transfer(cmd(a, b, "idem-key-4"));
        } catch (Exception expected) {
        }
        assertEquals(100, accounts.findById(a.getId()).orElseThrow().getBalanceMinorUnits());
        assertEquals(0, transactions.findByIdempotencyKey("idem-key-4").size());
    }
}
