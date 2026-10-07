package com.ledgerflow.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant startedAt;

    @Column
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false)
    private int checkedCount;

    @Column(nullable = false)
    private int mismatchCount;

    protected ReconciliationRun() {
        // JPA
    }

    public static ReconciliationRun start() {
        ReconciliationRun run = new ReconciliationRun();
        run.id = UUID.randomUUID();
        run.startedAt = Instant.now();
        run.status = Status.RUNNING;
        return run;
    }

    public void finish(int checked, int mismatches) {
        this.checkedCount = checked;
        this.mismatchCount = mismatches;
        this.status = Status.COMPLETED;
        this.finishedAt = Instant.now();
    }

    public void fail() {
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Status getStatus() { return status; }
    public int getCheckedCount() { return checkedCount; }
    public int getMismatchCount() { return mismatchCount; }
}
