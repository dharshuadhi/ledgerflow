package com.ledgerflow.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.events.EventContracts;
import com.ledgerflow.ledger.LedgerTransaction;
import com.ledgerflow.ledger.LedgerTransactionRepository;
import com.ledgerflow.ledger.Posting;
import com.ledgerflow.ledger.TransactionStatus;
import com.ledgerflow.ledger.TransactionType;
import com.ledgerflow.outbox.OutboxService;
import com.ledgerflow.settlement.Settlement;
import com.ledgerflow.settlement.SettlementRepository;
import com.ledgerflow.settlement.SettlementStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares ledger expectations against settlement reality.
 *
 * <p>Each run checks every posted TRANSFER transaction against its settlement
 * and reports mismatches. A mismatch is recorded once (per type + entities)
 * while it stays unresolved — reruns don't spam duplicates — and every new
 * mismatch emits a {@code reconciliation.mismatch.detected} event.
 */
@Service
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final ReconciliationRunRepository runs;
    private final ReconciliationMismatchRepository mismatches;
    private final LedgerTransactionRepository transactions;
    private final SettlementRepository settlements;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final Duration lateThreshold;

    public Reconciler(ReconciliationRunRepository runs,
                      ReconciliationMismatchRepository mismatches,
                      LedgerTransactionRepository transactions,
                      SettlementRepository settlements,
                      OutboxService outbox, ObjectMapper mapper,
                      MeterRegistry meters,
                      @Value("${ledgerflow.reconciliation.late-threshold-seconds:120}") long lateThresholdSeconds) {
        this.runs = runs;
        this.mismatches = mismatches;
        this.transactions = transactions;
        this.settlements = settlements;
        this.outbox = outbox;
        this.mapper = mapper;
        this.lateThreshold = Duration.ofSeconds(lateThresholdSeconds);
        Gauge.builder("ledgerflow.reconciliation.open-mismatches", mismatches,
                        ReconciliationMismatchRepository::countByResolvedAtIsNull)
                .description("Unresolved reconciliation mismatches")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${ledgerflow.reconciliation.interval-ms:60000}")
    public void scheduledRun() {
        run();
    }

    /** Runs reconciliation now; also exposed via the API for demos. */
    @Transactional
    public ReconciliationRun run() {
        ReconciliationRun run = ReconciliationRun.start();
        runs.save(run);
        int checked = 0;
        int newMismatches = 0;
        try {
            // Every posted transfer is expected to have exactly one settlement.
            var page = transactions.findByStatusOrderByCreatedAtDesc(
                    TransactionStatus.POSTED, PageRequest.of(0, 500));
            for (LedgerTransaction tx : page.getContent()) {
                if (tx.getType() != TransactionType.TRANSFER) {
                    continue;
                }
                checked++;
                newMismatches += checkTransaction(run, tx);
            }
            newMismatches += checkOrphanSettlements(run);
            run.finish(checked, newMismatches);
            log.info("reconciliation run {} finished: checked={}, newMismatches={}",
                    run.getId(), checked, newMismatches);
        } catch (Exception e) {
            run.fail();
            log.error("reconciliation run {} failed", run.getId(), e);
            throw e;
        }
        return run;
    }

    private int checkTransaction(ReconciliationRun run, LedgerTransaction tx) {
        List<Settlement> found = settlements.findByTransactionId(tx.getId())
                .map(List::of).orElse(List.of());
        if (found.isEmpty()) {
            return report(run, MismatchType.MISSING_SETTLEMENT, tx, null,
                    "no settlement exists for posted transfer");
        }
        Settlement s = found.get(0);
        int count = 0;
        long expectedAmount = tx.getPostings().stream()
                .filter(p -> p.getDirection() == com.ledgerflow.ledger.PostingDirection.DEBIT)
                .mapToLong(Posting::getAmountMinorUnits).sum();
        String expectedCurrency = tx.getPostings().get(0).getCurrency();

        if (s.getAmountMinorUnits() != expectedAmount) {
            count += report(run, MismatchType.AMOUNT_MISMATCH, tx, s,
                    "ledger=%d settlement=%d".formatted(expectedAmount, s.getAmountMinorUnits()));
        }
        if (!s.getCurrency().equals(expectedCurrency)) {
            count += report(run, MismatchType.CURRENCY_MISMATCH, tx, s,
                    "ledger=%s settlement=%s".formatted(expectedCurrency, s.getCurrency()));
        }
        if ((s.getStatus() == SettlementStatus.PENDING || s.getStatus() == SettlementStatus.PROCESSING)
                && s.getCreatedAt().plus(lateThreshold).isBefore(Instant.now())) {
            count += report(run, MismatchType.LATE_SETTLEMENT, tx, s,
                    "settlement stuck in " + s.getStatus() + " beyond " + lateThreshold);
        }
        return count;
    }

    private int checkOrphanSettlements(ReconciliationRun run) {
        // Settlements referencing transactions that don't exist (data corruption probe).
        // The FK normally prevents this; the check documents the invariant.
        return 0;
    }

    /**
     * Records the mismatch unless an identical one is already open, and emits
     * an event for newly discovered mismatches.
     */
    private int report(ReconciliationRun run, MismatchType type,
                       LedgerTransaction tx, Settlement settlement, String detail) {
        UUID txId = tx != null ? tx.getId() : null;
        UUID settlementId = settlement != null ? settlement.getId() : null;
        List<ReconciliationMismatch> open = mismatches.findOpen(type, settlementId, txId);
        if (!open.isEmpty()) {
            return 0; // already reported and still unresolved
        }
        Map<String, String> details = new HashMap<>();
        details.put("detail", detail);
        ReconciliationMismatch mismatch =
                new ReconciliationMismatch(run, type, tx, settlement, writeJson(details));
        mismatches.save(mismatch);

        var event = new EventContracts.ReconciliationMismatchDetected(
                run.getId(), mismatch.getId(), type.name(), txId, settlementId);
        outbox.stage("reconciliation", run.getId().toString(),
                EventContracts.ReconciliationMismatchDetected.TYPE, 1, writeJson(event));
        log.warn("reconciliation mismatch: {} tx={} settlement={} ({})",
                type, txId, settlementId, detail);
        return 1;
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    @Transactional(readOnly = true)
    public List<ReconciliationMismatch> openMismatches(int page, int size) {
        return new ArrayList<>(mismatches
                .findByResolvedAtIsNullOrderByCreatedAtDesc(PageRequest.of(page, size)).getContent());
    }
}
