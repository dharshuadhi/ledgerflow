package com.ledgerflow.projection;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.Account;
import com.ledgerflow.ledger.AccountRepository;
import com.ledgerflow.transfer.TransferService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the balance projection is a disposable read model: events flow into
 * it, it can be destroyed and rebuilt from postings, and inconsistencies are
 * detectable.
 */
class ProjectionRebuildIT extends AbstractIntegrationTest {

    @Autowired
    private TransferService transfers;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private BalanceProjectionService projectionService;

    @Autowired
    private BalanceProjectionRepository projectionRepository;

    private UUID funded(String number, long balance) {
        Account a = new Account(number, "owner-" + number, "USD");
        a.setBalanceMinorUnits(balance);
        return accounts.save(a).getId();
    }

    @Test
    void projectionFollowsEventsAndRebuildsFromPostings() {
        UUID a = funded("PR-A", 50_000);
        UUID b = funded("PR-B", 0);

        transfers.transfer(new TransferService.TransferCommand(
                a, b, 12_000, "USD", "projection-test", "pr-key-1", "SUCCESS"));

        // The async consumer folds the event into the read model.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertEquals(12_000L, projectionService.projectedBalance(b).orElse(-1L));
            assertEquals(38_000L, projectionService.projectedBalance(a).orElse(-1L));
        });

        // Simulate corruption: destroy the read model entirely.
        projectionRepository.deleteAll();
        assertTrue(projectionService.projectedBalance(a).isEmpty());

        // Rebuild purely from the authoritative posting history.
        int rebuilt = projectionService.rebuildFromPostings();
        assertEquals(2, rebuilt);
        assertEquals(38_000L, projectionService.projectedBalance(a).orElseThrow());
        assertEquals(12_000L, projectionService.projectedBalance(b).orElseThrow());

        // And the verifier agrees: no inconsistencies.
        assertTrue(projectionService.findInconsistencies().isEmpty());
    }

    @Test
    void inconsistencyIsDetected() {
        UUID a = funded("PR-C", 20_000);
        UUID b = funded("PR-D", 0);
        transfers.transfer(new TransferService.TransferCommand(
                a, b, 5_000, "USD", "x", "pr-key-2", "SUCCESS"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertTrue(projectionService.projectedBalance(b).isPresent()));

        // Tamper with the read model directly (simulating a bug or bad deploy).
        BalanceProjection corrupted = projectionRepository.findById(b).orElseThrow();
        projectionRepository.applyDelta(b, "USD", 999_999, UUID.randomUUID());

        var problems = projectionService.findInconsistencies();
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains(b.toString()));
    }
}
