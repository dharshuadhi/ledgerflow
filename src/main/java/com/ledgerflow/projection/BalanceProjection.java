package com.ledgerflow.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Derived balance read model.
 *
 * <p>This table is <strong>not</strong> authoritative. It is a fold over the
 * {@code ledger.transaction.posted} event stream and can be dropped and rebuilt
 * from postings at any time. If it ever disagrees with the postings-derived
 * balance, the postings win.
 */
@Entity
@Table(name = "balance_projections")
public class BalanceProjection {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID accountId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long balanceMinorUnits;

    @Column(columnDefinition = "uuid")
    private UUID lastEventId;

    @Column(nullable = false)
    private Instant updatedAt;

    protected BalanceProjection() {
    }

    public BalanceProjection(UUID accountId, String currency, long balanceMinorUnits, UUID lastEventId) {
        this.accountId = accountId;
        this.currency = currency;
        this.balanceMinorUnits = balanceMinorUnits;
        this.lastEventId = lastEventId;
        this.updatedAt = Instant.now();
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinorUnits() {
        return balanceMinorUnits;
    }

    public void applyDelta(long delta, UUID eventId) {
        this.balanceMinorUnits = Math.addExact(this.balanceMinorUnits, delta);
        this.lastEventId = eventId;
        this.updatedAt = Instant.now();
    }
}
