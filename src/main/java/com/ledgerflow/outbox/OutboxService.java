package com.ledgerflow.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stages outbox events inside the caller's business transaction.
 *
 * <p>Call this from the same {@code @Transactional} method that writes the
 * business rows. If the business transaction rolls back, the event row rolls
 * back with it — the event can never exist without its state change.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;

    public OutboxService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public OutboxEvent stage(String aggregateType, String aggregateId,
                             String eventType, int eventVersion, String payloadJson) {
        return repository.save(new OutboxEvent(
                aggregateType, aggregateId, eventType, eventVersion, payloadJson));
    }
}
