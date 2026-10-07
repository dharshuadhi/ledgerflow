package com.ledgerflow.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.events.EventContracts;
import com.ledgerflow.events.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** Creates settlements from posted ledger transactions. */
@Component
public class SettlementEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementEventConsumer.class);

    private final SettlementService settlements;
    private final ObjectMapper mapper;

    public SettlementEventConsumer(SettlementService settlements, ObjectMapper mapper) {
        this.settlements = settlements;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KafkaTopics.LEDGER_EVENTS,
            groupId = "ledgerflow-settlement",
            containerFactory = "manualAckFactory")
    public void onTransactionPosted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            var event = mapper.readValue(record.value(), EventContracts.TransactionPosted.class);
            settlements.createFromLedgerEvent(event.eventId(), event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("settlement creation failed at offset {}", record.offset(), e);
            throw new IllegalStateException("settlement creation failed, will retry", e);
        }
    }
}
