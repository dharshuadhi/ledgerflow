package com.ledgerflow.ledger;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The atomic unit of money movement.
 *
 * <p><strong>Double-entry invariant</strong> (enforced in {@link #validateBalanced()} before
 * anything is persisted): within one transaction, postings must balance <em>per
 * currency</em> — total debits equal total credits for every currency present — and a
 * transaction must contain at least two postings. Money can therefore never be
 * created or destroyed by a bug in a single write path; it can only move.
 */
@Entity
@Table(name = "ledger_transactions", indexes = {
        @Index(name = "ix_ledger_tx_type", columnList = "type"),
        @Index(name = "ix_ledger_tx_status", columnList = "status"),
        @Index(name = "ix_ledger_tx_idempotency", columnList = "idempotencyKey", unique = true)
})
public class LedgerTransaction {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionStatus status;

    /** Links back to the idempotency record that produced this transaction, if any. */
    @Column(length = 64)
    private String idempotencyKey;

    @Column(length = 512)
    private String description;

    /** Free-form context, e.g. FX rate metadata. Never used for money math. */
    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> metadata = new HashMap<>();

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Posting> postings = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    public LedgerTransaction(TransactionType type, String idempotencyKey, String description) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.status = TransactionStatus.POSTED;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public void addPosting(Posting posting) {
        postings.add(posting);
    }

    /**
     * Enforces the double-entry invariant per currency. Called by the ledger
     * service inside the same database transaction that persists the postings,
     * so a violation can never be half-written.
     */
    public void validateBalanced() {
        if (postings.size() < 2) {
            throw new UnbalancedTransactionException(
                    "transaction requires at least two postings, got " + postings.size());
        }
        Map<String, Long> netByCurrency = new HashMap<>();
        for (Posting p : postings) {
            long signed = p.getDirection() == PostingDirection.DEBIT
                    ? p.getAmountMinorUnits()
                    : -p.getAmountMinorUnits();
            netByCurrency.merge(p.getCurrency(), signed, Long::sum);
        }
        List<String> unbalanced = netByCurrency.entrySet().stream()
                .filter(e -> e.getValue() != 0)
                .map(e -> e.getKey() + " net=" + e.getValue())
                .toList();
        if (!unbalanced.isEmpty()) {
            throw new UnbalancedTransactionException("debts != credits for: " + unbalanced);
        }
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void markReversed() {
        this.status = TransactionStatus.REVERSED;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public List<Posting> getPostings() {
        return postings;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
