package com.ledgerflow.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account.
 *
 * <p><strong>Important:</strong> {@code balanceMinorUnits} is a maintained cache for fast
 * reads. It is <em>not</em> authoritative — the sum of {@link Posting} rows is. A
 * background verifier recomputes balances from postings and raises a mismatch event
 * if the cache ever diverges. See ADR-001 and ADR-005.
 *
 * <p>Concurrent transfers use optimistic locking ({@code version}): two writers racing
 * on the same account cause one {@code OptimisticLockException}, which the transfer
 * service retries with jittered backoff. This keeps hot accounts correct without
 * serializing every transfer behind {@code SELECT ... FOR UPDATE}. See ADR-007.
 */
@Entity
@Table(name = "accounts", indexes = {
        @Index(name = "ix_accounts_number", columnList = "accountNumber", unique = true),
        @Index(name = "ix_accounts_status", columnList = "status")
})
public class Account {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, unique = true, length = 32)
    private String accountNumber;

    @Column(nullable = false, length = 128)
    private String ownerName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    /** Cached balance in minor units. Authoritative truth lives in postings. */
    @Column(nullable = false)
    private long balanceMinorUnits;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public Account(String accountNumber, String ownerName, String currency) {
        this.id = UUID.randomUUID();
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.balanceMinorUnits = 0L;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getBalanceMinorUnits() {
        return balanceMinorUnits;
    }

    public void setBalanceMinorUnits(long balanceMinorUnits) {
        this.balanceMinorUnits = balanceMinorUnits;
        this.updatedAt = Instant.now();
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
