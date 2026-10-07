package com.ledgerflow.ledger;

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
import java.time.Instant;
import java.util.UUID;

/**
 * One side of a double-entry movement.
 *
 * <p>Postings are append-only. There is deliberately no update or delete path:
 * corrections are modeled as reversal transactions so the full history stays
 * auditable.
 */
@Entity
@Table(name = "postings", indexes = {
        @Index(name = "ix_postings_transaction", columnList = "transaction_id"),
        @Index(name = "ix_postings_account_created", columnList = "account_id, createdAt")
})
public class Posting {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PostingDirection direction;

    /** Always positive; the direction carries the sign. DB enforces {@code > 0}. */
    @Column(nullable = false)
    private long amountMinorUnits;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Posting() {
    }

    public Posting(LedgerTransaction transaction, Account account,
                   PostingDirection direction, long amountMinorUnits, String currency) {
        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("posting amount must be positive");
        }
        this.id = UUID.randomUUID();
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amountMinorUnits = amountMinorUnits;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public Account getAccount() {
        return account;
    }

    public PostingDirection getDirection() {
        return direction;
    }

    public long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
