package com.ledgerflow.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.events.EventContracts;
import com.ledgerflow.events.KafkaTopics;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code ledger.transaction.posted} events into the balance read model.
 *
 * <p>Delivery is at-least-once: the event id is checked against
 * {@code processed_events} before anything is applied, and offsets are
 * acknowledged only after the database transaction commits. A crash between
 * "applied" and "acknowledged" simply replays an already-recorded event id.
 */
@Component
public class LedgerEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventConsumer.class);
    private static final String CONSUMER_NAME = "balance-projection";

    private final BalanceProjectionService projections;
    private final ObjectMapper mapper;

    public LedgerEventConsumer(BalanceProjectionService projections, ObjectMapper mapper) {
        this.projections = projections;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KafkaTopics.LEDGER_EVENTS,
            groupId = "ledgerflow-projection",
            containerFactory = "manualAckFactory")
    public void onTransactionPosted(ConsumerRecord<String, String> record,
                                    @Header(value = "event-id", required = false) String eventIdHeader,
                                    Acknowledgment ack) {
        try {
            var event = mapper.readValue(record.value(), EventContracts.TransactionPosted.class);
            UUID eventId = event.eventId();
            if (eventIdHeader != null && !eventIdHeader.isBlank()) {
                try {
                    eventId = UUID.fromString(eventIdHeader);
                } catch (IllegalArgumentException ignored) {
                    // fall back to the payload's event id
                }
            }
            if (projections.alreadyProcessed(eventId)) {
                log.debug("skipping already-processed event {}", eventId);
                ack.acknowledge();
                return;
            }
            projections.apply(event);
            projections.markProcessed(eventId, CONSUMER_NAME);
            ack.acknowledge();
            log.debug("projected balances for transaction {}", event.transactionId());
        } catch (Exception e) {
            // Do NOT acknowledge: the record will be redelivered. Poison records
            // are the relay's problem (DLQ); here we rely on Spring Kafka's
            // error handler + retries. See docs/failure-model.md.
            log.error("failed to project ledger event at offset {}", record.offset(), e);
            throw new IllegalStateException("projection failed, will retry", e);
        }
    }
}
