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

/** Drives the settlement simulator from {@code settlement.requested} events. */
@Component
public class SettlementRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementRequestConsumer.class);

    private final SettlementSimulator simulator;
    private final ObjectMapper mapper;

    public SettlementRequestConsumer(SettlementSimulator simulator, ObjectMapper mapper) {
        this.simulator = simulator;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KafkaTopics.SETTLEMENT_EVENTS,
            groupId = "ledgerflow-settlement-sim",
            containerFactory = "manualAckFactory")
    public void onSettlementRequested(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            var event = mapper.readValue(record.value(), EventContracts.SettlementRequested.class);
            simulator.onSettlementRequested(event.settlementId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("settlement simulation failed at offset {}", record.offset(), e);
            throw new IllegalStateException("settlement simulation failed, will retry", e);
        }
    }
}
