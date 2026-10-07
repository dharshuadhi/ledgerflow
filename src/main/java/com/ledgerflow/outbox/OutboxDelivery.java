package com.ledgerflow.outbox;

import com.ledgerflow.events.KafkaTopics;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a single outbox event to Kafka.
 *
 * <p>Failure behavior: publish failures back off exponentially
 * (1s → 2s → 4s … capped at 5 minutes). After {@value #MAX_ATTEMPTS} attempts the
 * row is marked POISON, copied to the dead-letter topic, and left for a human.
 */
@Service
public class OutboxDelivery {

    private static final Logger log = LoggerFactory.getLogger(OutboxDelivery.class);
    static final int MAX_ATTEMPTS = 10;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final Duration maxBackoff;

    public OutboxDelivery(OutboxEventRepository repository, KafkaTemplate<String, String> kafka,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${ledgerflow.outbox.max-backoff-seconds:300}") long maxBackoffSeconds) {
        this.repository = repository;
        this.kafka = kafka;
        this.maxBackoff = Duration.ofSeconds(maxBackoffSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(UUID id) {
        OutboxEvent event = repository.lockById(id).orElse(null);
        if (event == null
                || event.getStatus() != OutboxStatus.PENDING
                || event.getNextAttemptAt().isAfter(Instant.now())) {
            return; // handled by a racing relay, or not due yet
        }
        try {
            String topic = KafkaTopics.topicForEventType(event.getEventType());
            // The aggregate id is the Kafka key: all events for one aggregate keep
            // partition order. Consumers must still tolerate redelivery.
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, event.getAggregateId(), event.getPayload());
            record.headers().add("event-id", event.getId().toString().getBytes());
            record.headers().add("event-type", event.getEventType().getBytes());
            kafka.send(record).get(30, TimeUnit.SECONDS);
            event.markPublished();
            log.debug("published outbox event {} to {}", id, topic);
        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : e.toString();
            if (event.getPublishAttempts() + 1 >= MAX_ATTEMPTS) {
                event.markPoison("exhausted retries: " + error);
                writeDeadLetter(event);
                log.error("outbox event {} poisoned after {} attempts", id, MAX_ATTEMPTS);
            } else {
                long backoffSec = Math.min(1L << event.getPublishAttempts(), maxBackoff.getSeconds());
                event.markAttemptFailed(error, Instant.now().plus(backoffSec, ChronoUnit.SECONDS));
                log.warn("outbox event {} failed (attempt {}), retry in {}s",
                        id, event.getPublishAttempts(), backoffSec);
            }
        }
    }

    private void writeDeadLetter(OutboxEvent event) {
        try {
            kafka.send(KafkaTopics.DEAD_LETTER, event.getAggregateId(), event.getPayload())
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("failed to write poison event {} to DLQ", event.getId(), e);
        }
    }
}
