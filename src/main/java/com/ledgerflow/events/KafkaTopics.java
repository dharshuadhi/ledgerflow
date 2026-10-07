package com.ledgerflow.events;

/**
 * Kafka topic names. Topics are versioned so a breaking contract change ships as
 * a new topic instead of silently breaking existing consumers.
 */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    public static final String LEDGER_EVENTS = "ledger.events.v1";
    public static final String SETTLEMENT_EVENTS = "settlement.events.v1";
    public static final String RECONCILIATION_EVENTS = "reconciliation.events.v1";

    /** Poison events that exhausted relay retries land here for inspection. */
    public static final String DEAD_LETTER = "ledger.events.dlq";

    public static String topicForEventType(String eventType) {
        if (eventType.startsWith("ledger.")) {
            return LEDGER_EVENTS;
        }
        if (eventType.startsWith("settlement.")) {
            return SETTLEMENT_EVENTS;
        }
        if (eventType.startsWith("reconciliation.")) {
            return RECONCILIATION_EVENTS;
        }
        throw new IllegalArgumentException("no topic mapping for event type: " + eventType);
    }
}
