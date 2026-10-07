package com.ledgerflow.outbox;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.events.KafkaTopics;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Proves the outbox relay publishes staged events to Kafka and marks them
 * published — the atomic commit → async publish contract.
 */
class OutboxRelayIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxService outbox;

    @Autowired
    private OutboxEventRepository repository;

    @Test
    void stagedEventIsPublishedToKafka() {
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"hello\":\"world\"}";
        outbox.stage("test-aggregate", aggregateId.toString(),
                "ledger.transaction.posted.v1", 1, payload);

        // Raw consumer (not part of the app) to observe what the relay publishes.
        String brokers = System.getProperty("spring.embedded.kafka.brokers");
        var consumerProps = KafkaTestUtils.consumerProps(brokers, "outbox-it", "false");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        Thread poller = new Thread(() -> {
            try (var consumer = new KafkaConsumer<String, String>(consumerProps)) {
                consumer.subscribe(java.util.List.of(KafkaTopics.LEDGER_EVENTS));
                long deadline = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < deadline && seen.isEmpty()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    records.forEach(r -> {
                        if (aggregateId.toString().equals(r.key())) {
                            seen.add(r.value());
                        }
                    });
                }
            }
        });
        poller.setDaemon(true);
        poller.start();

        try {
            String value = seen.poll(30, TimeUnit.SECONDS);
            assertEquals(payload, value, "relay did not publish the staged event");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        // And the row is marked published so it won't be redelivered.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertTrue(repository.findAll().stream()
                        .allMatch(e -> e.getStatus() == OutboxStatus.PUBLISHED)));
    }

    @Test
    void failedPublishBacksOffAndEventuallyPoisons() {
        // Stage an event with an unroutable type: topicForEventType throws,
        // which exercises the retry → poison path without touching Kafka.
        OutboxEvent event = repository.save(new OutboxEvent(
                "agg", UUID.randomUUID().toString(), "unknown.bogus-type", 1, "{}"));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            OutboxEvent reloaded = repository.findById(event.getId()).orElseThrow();
            assertEquals(OutboxStatus.POISON, reloaded.getStatus());
        });
    }
}
