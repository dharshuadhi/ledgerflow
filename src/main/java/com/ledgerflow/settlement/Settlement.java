package com.ledgerflow.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import com.ledgerflow.ledger.LedgerTransaction;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlements", indexes = {
        @Index(name = "ix_settlements_status", columnList = "status")
})
public class Settlement {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private LedgerTransaction transaction;

    @Column(nullable = false)
    private long amountMinorUnits;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SettlementStatus status;

    @Column(length = 128)
    private String providerRef;

    /** Webhook deduplication key: the provider's own event id. */
    @Column(length = 128)
    private String providerEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SettlementScenario scenario;

    @Column(nullable = false)
    private int attempts;

    @Column(columnDefinition = "text")
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant settledAt;

    /** For DELAYED scenario: the provider will not confirm before this time. */
    @Column
    private Instant notBefore;

    protected Settlement() {
    }

    public Settlement(LedgerTransaction transaction, long amountMinorUnits,
                      String currency, SettlementScenario scenario) {
        this.id = UUID.randomUUID();
        this.transaction = transaction;
        this.amountMinorUnits = amountMinorUnits;
        this.currency = currency;
        this.status = SettlementStatus.PENDING;
        this.scenario = scenario;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markProcessing() {
        this.status = SettlementStatus.PROCESSING;
        this.attempts++;
        this.updatedAt = Instant.now();
    }

    public void markSettled(String providerRef, String providerEventId) {
        this.status = SettlementStatus.SETTLED;
        this.providerRef = providerRef;
        this.providerEventId = providerEventId;
        this.settledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = SettlementStatus.FAILED;
        this.lastError = error;
        this.updatedAt = Instant.now();
    }

    public void recordAttemptError(String error) {
        this.attempts++;
        this.lastError = error;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    public String getCurrency() {
        return currency;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public SettlementScenario getScenario() {
        return scenario;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Instant notBefore) {
        this.notBefore = notBefore;
        this.updatedAt = Instant.now();
    }
}
