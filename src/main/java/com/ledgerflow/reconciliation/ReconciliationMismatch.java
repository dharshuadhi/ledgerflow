package com.ledgerflow.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import com.ledgerflow.ledger.LedgerTransaction;
import com.ledgerflow.settlement.Settlement;
import java.time.Instant;
import java.util.UUID;

/**
 * One observed inconsistency. Mismatches are never auto-resolved: they stay
 * open until a human marks them resolved, so nothing is silently hidden.
 */
@Entity
@Table(name = "reconciliation_mismatches")
public class ReconciliationMismatch {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private ReconciliationRun run;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private MismatchType mismatchType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private Settlement settlement;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String details;

    @Column
    private Instant resolvedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconciliationMismatch() {
    }

    public ReconciliationMismatch(ReconciliationRun run, MismatchType mismatchType,
                                  LedgerTransaction transaction, Settlement settlement,
                                  String details) {
        this.id = UUID.randomUUID();
        this.run = run;
        this.mismatchType = mismatchType;
        this.transaction = transaction;
        this.settlement = settlement;
        this.details = details;
        this.createdAt = Instant.now();
    }

    public void resolve() {
        this.resolvedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public ReconciliationRun getRun() { return run; }
    public MismatchType getMismatchType() { return mismatchType; }
    public LedgerTransaction getTransaction() { return transaction; }
    public Settlement getSettlement() { return settlement; }
    public String getDetails() { return details; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
