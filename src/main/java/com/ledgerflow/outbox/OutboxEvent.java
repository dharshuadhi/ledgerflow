package com.ledgerflow.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox record.
 *
 * <p>Business rows and outbox rows are written in <strong>one</strong> database
 * transaction. A relay then publishes PENDING rows to Kafka. This gives reliable
 * publishing without distributed (XA) transactions: the database is the single
 * source of truth for "what still needs to be sent".
 *
 * <p>Delivery is at-least-once by design. Consumers deduplicate on the event id.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 64)
    private String aggregateType;

    @Column(nullable = false, length = 64)
    private String aggregateId;

    @Column(nullable = false, length = 128)
    private String eventType;

    @Column(nullable = false)
    private int eventVersion = 1;

    /** Canonical event JSON, including the event id for consumer dedup. */
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    private int publishAttempts;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private Instant publishedAt;

    /** Next time the relay may attempt delivery (backoff after failures). */
    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(columnDefinition = "text")
    private String lastError;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, String aggregateId,
                       String eventType, int eventVersion, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.nextAttemptAt = Instant.now();
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public void markAttemptFailed(String error, Instant nextAttempt) {
        this.publishAttempts++;
        this.lastError = error;
        this.nextAttemptAt = nextAttempt;
    }

    public void markPoison(String error) {
        this.status = OutboxStatus.POISON;
        this.lastError = error;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getPublishAttempts() {
        return publishAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
