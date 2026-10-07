package com.ledgerflow.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.Account;
import com.ledgerflow.ledger.AccountRepository;
import com.ledgerflow.ledger.PostingRepository;
import java.util.ArrayList;
import java.util.List;
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
 * Hammers the ledger with concurrent transfers and asserts the accounting
 * invariant holds: debits == credits globally, and every account balance
 * equals its postings-derived balance.
 */
class TransferConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private TransferService transfers;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PostingRepository postings;

    @Test
    void manyConcurrentTransfersPreserveInvariants() throws Exception {
        int accountCount = 6;
        List<Account> accts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            Account a = new Account("CC-" + i, "owner", "USD");
            a.setBalanceMinorUnits(1_000_000);
            accts.add(accounts.save(a));
        }
        // Capture ids to avoid lazy-loading across threads.
        List<UUID> ids = accts.stream().map(Account::getId).toList();

        int threads = 16;
        int transfersPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadIdx = t;
            futures.add(pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < transfersPerThread; i++) {
                    UUID from = ids.get((threadIdx + i) % accountCount);
                    UUID to = ids.get((threadIdx + i + 1) % accountCount);
                    try {
                        transfers.transfer(new TransferService.TransferCommand(
                                from, to, 100, "USD", "concurrency",
                                "cc-%d-%d".formatted(threadIdx, i), "SUCCESS"));
                        ok.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (var f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int total = threads * transfersPerThread;
        assertEquals(total, ok.get(), "all transfers should succeed, failed=" + failed.get());

        // Global invariant: net across all postings is zero per currency.
        var nets = postings.netPerCurrency();
        assertTrue(nets.stream().allMatch(r -> ((Number) r[1]).longValue() == 0L),
                "global invariant broken: " + nets);

        // Per-account: cached balance == postings-derived balance, and total conserved.
        long totalCached = 0;
        for (UUID id : ids) {
            Account a = accounts.findById(id).orElseThrow();
            long authoritative = postings.authoritativeBalance(id, "USD");
            assertEquals(authoritative, a.getBalanceMinorUnits(),
                    "cache diverged for account " + id);
            totalCached += a.getBalanceMinorUnits();
        }
        assertEquals(6_000_000L, totalCached, "money was created or destroyed");
    }

    @Test
    void hotAccountSurvivesContention() throws Exception {
        Account hot = new Account("HOT", "owner", "USD");
        hot.setBalanceMinorUnits(10_000_000);
        hot = accounts.save(hot);
        UUID hotId = hot.getId();

        List<UUID> sinks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Account s = accounts.save(new Account("SINK-" + i, "owner", "USD"));
            sinks.add(s.getId());
        }

        int threads = 12;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int ti = t;
            futures.add(pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < perThread; i++) {
                    try {
                        transfers.transfer(new TransferService.TransferCommand(
                                hotId, sinks.get(ti % sinks.size()), 50, "USD", "hot",
                                "hot-%d-%d".formatted(ti, i), "SUCCESS"));
                        ok.incrementAndGet();
                    } catch (Exception e) {
                        // Optimistic-lock retries are bounded; count failures.
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (var f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        long expectedOut = (long) ok.get() * 50;
        long hotBalance = accounts.findById(hotId).orElseThrow().getBalanceMinorUnits();
        assertEquals(10_000_000 - expectedOut, hotBalance);
        assertEquals(10_000_000 - expectedOut,
                postings.authoritativeBalance(hotId, "USD"));
    }
}
