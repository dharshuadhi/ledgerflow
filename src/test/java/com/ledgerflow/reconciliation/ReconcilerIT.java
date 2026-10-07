package com.ledgerflow.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.common.Money;
import com.ledgerflow.ledger.Account;
import com.ledgerflow.ledger.AccountRepository;
import com.ledgerflow.ledger.LedgerService;
import com.ledgerflow.settlement.Settlement;
import com.ledgerflow.settlement.SettlementRepository;
import com.ledgerflow.settlement.SettlementScenario;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reconciliation detects real discrepancies without hiding them.
 */
class ReconcilerIT extends AbstractIntegrationTest {

    @Autowired
    private Reconciler reconciler;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private SettlementRepository settlements;

    @Autowired
    private ReconciliationMismatchRepository mismatches;

    private Account funded(String number, long balance) {
        Account a = new Account(number, "owner-" + number, "USD");
        a.setBalanceMinorUnits(balance);
        return accounts.save(a);
    }

    @Test
    void missingSettlementIsReported() {
        Account a = funded("RC-A", 10_000);
        Account b = funded("RC-B", 0);
        // Post directly through the ledger so no settlement consumer creates one.
        var tx = ledger.postTransfer(a.getId(), b.getId(), Money.of(500, "USD"), "rc-key-1", "x");

        ReconciliationRun run = reconciler.run();

        assertEquals(1, run.getCheckedCount());
        assertEquals(1, run.getMismatchCount());
        var open = mismatches.findOpen(MismatchType.MISSING_SETTLEMENT, null, tx.getId());
        assertEquals(1, open.size());
    }

    @Test
    void amountMismatchIsReported() {
        Account a = funded("RC-C", 10_000);
        Account b = funded("RC-D", 0);
        var tx = ledger.postTransfer(a.getId(), b.getId(), Money.of(500, "USD"), "rc-key-2", "x");
        // Corrupt the settlement amount (simulating a provider-side discrepancy).
        settlements.save(new Settlement(tx, 499, "USD", SettlementScenario.SUCCESS));

        ReconciliationRun run = reconciler.run();

        assertEquals(1, run.getMismatchCount());
        var open = mismatches.findOpen(MismatchType.AMOUNT_MISMATCH, null, tx.getId());
        assertEquals(1, open.size());
        assertTrue(open.get(0).getDetails().contains("499"));
    }

    @Test
    void settledTransferProducesNoMismatch() {
        Account a = funded("RC-E", 10_000);
        Account b = funded("RC-F", 0);
        var tx = ledger.postTransfer(a.getId(), b.getId(), Money.of(700, "USD"), "rc-key-3", "x");
        Settlement s = new Settlement(tx, 700, "USD", SettlementScenario.SUCCESS);
        s.markProcessing();
        s.markSettled("REF-1", "prov-1");
        settlements.save(s);

        ReconciliationRun run = reconciler.run();

        assertEquals(0, run.getMismatchCount());
    }

    @Test
    void stuckSettlementIsFlaggedLate() throws Exception {
        Account a = funded("RC-G", 10_000);
        Account b = funded("RC-H", 0);
        var tx = ledger.postTransfer(a.getId(), b.getId(), Money.of(300, "USD"), "rc-key-4", "x");
        Settlement s = new Settlement(tx, 300, "USD", SettlementScenario.TIMEOUT);
        s.markProcessing();
        // Backdate past the late threshold (2s in tests via property? default 120s).
        // Use reflection to age it deterministically.
        Field createdAt = Settlement.class.getDeclaredField("createdAt");
        createdAt.setAccessible(true);
        createdAt.set(s, Instant.now().minus(1, ChronoUnit.HOURS));
        settlements.save(s);

        ReconciliationRun run = reconciler.run();

        var open = mismatches.findOpen(MismatchType.LATE_SETTLEMENT, s.getId(), tx.getId());
        assertEquals(1, open.size());
    }

    @Test
    void rerunDoesNotDuplicateOpenMismatches() {
        Account a = funded("RC-I", 10_000);
        Account b = funded("RC-J", 0);
        ledger.postTransfer(a.getId(), b.getId(), Money.of(111, "USD"), "rc-key-5", "x");

        reconciler.run();
        reconciler.run();

        long openCount = mismatches.countByResolvedAtIsNull();
        assertEquals(1, openCount, "reruns must not re-report the same open mismatch");
    }
}
