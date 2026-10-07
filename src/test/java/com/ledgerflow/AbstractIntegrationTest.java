package com.ledgerflow;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration tests against <strong>real</strong> PostgreSQL (embedded binary)
 * and a real Kafka broker (embedded, in-JVM).
 *
 * <p>No Docker required: the sandbox blocks bridge networking, so Testcontainers
 * can't run here. The embedded broker speaks the real Kafka protocol and the
 * embedded Postgres runs the real server — Flyway migrations, SQL dialect, and
 * consumer semantics are all genuinely exercised. The docker-compose stack is
 * validated separately in CI, where Docker is available.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "ledger.events.v1",
            "settlement.events.v1",
            "reconciliation.events.v1",
            "ledger.events.dlq"
        },
        brokerProperties = {
            "auto.create.topics.enable=true",
            "offsets.topic.replication.factor=1",
            "transaction.state.log.replication.factor=1"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    /** One Postgres per JVM; databases are per-test-class via the JDBC URL. */
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder()
                    .setPort(0) // ephemeral port
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("embedded postgres failed to start", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Each test class gets a fresh database on the shared server.
        String db = "test_" + System.nanoTime();
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl("postgres", db));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // Spring Boot maps the embedded broker address automatically, but be explicit.
        registry.add("spring.kafka.bootstrap-servers",
                () -> System.getProperty("spring.embedded.kafka.brokers"));
        // Keep background jobs quiet-ish during tests.
        registry.add("ledgerflow.reconciliation.interval-ms", () -> "3600000");
        registry.add("ledgerflow.outbox.poll-ms", () -> "200");
        registry.add("ledgerflow.outbox.max-backoff-seconds", () -> "1");
    }
}
