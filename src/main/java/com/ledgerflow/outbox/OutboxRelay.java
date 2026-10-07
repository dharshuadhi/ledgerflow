package com.ledgerflow.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox table to Kafka.
 *
 * <p>Crash behavior: the relay holds no in-memory state. If it dies mid-batch,
 * the rows stay PENDING and the next poll (this instance after restart, or any
 * other instance) republishes them. Kafka may therefore receive duplicates —
 * consumers deduplicate on the event id, so this is safe by design.
 *
 * <p>Locking discipline: {@link #drain} claims a batch in one short transaction
 * (locks released immediately), then each event is delivered in its own
 * transaction with an exclusive row lock ({@link OutboxDelivery}). A poison
 * event can never roll back — or double-publish — its siblings.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final int BATCH_SIZE = 100;

    private final OutboxEventRepository repository;
    private final OutboxDelivery delivery;

    public OutboxRelay(OutboxEventRepository repository,
                       OutboxDelivery delivery,
                       MeterRegistry meters) {
        this.repository = repository;
        this.delivery = delivery;
        Gauge.builder("ledgerflow.outbox.backlog", repository, r -> r.countByStatus(OutboxStatus.PENDING))
                .description("Outbox events waiting to be published")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${ledgerflow.outbox.poll-ms:1000}")
    public void drain() {
        List<UUID> ids = claimBatch();
        if (!ids.isEmpty()) {
            log.debug("outbox relay claimed {} events", ids.size());
        }
        for (UUID id : ids) {
            try {
                delivery.deliver(id);
            } catch (Exception e) {
                // Delivery records its own retry/poison state; never let one
                // event kill the drain loop.
                log.error("outbox delivery failed for {}", id, e);
            }
        }
    }

    @Transactional
    public List<UUID> claimBatch() {
        return repository.claimDueBatch(Instant.now(), BATCH_SIZE).stream()
                .map(OutboxEvent::getId)
                .toList();
    }
}
