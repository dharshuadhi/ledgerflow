package com.ledgerflow.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side deduplication record. Under at-least-once delivery the same
 * event may arrive twice (relay retry, consumer restart); the event id makes
 * the second delivery a no-op.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID eventId;

    @Column(nullable = false, length = 128)
    private String consumer;

    @Column(nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String consumer) {
        this.eventId = eventId;
        this.consumer = consumer;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }
}
