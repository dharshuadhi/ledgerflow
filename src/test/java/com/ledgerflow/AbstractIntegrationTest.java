package com.ledgerflow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests against real PostgreSQL 16 and Redpanda (Kafka API)
 * via Testcontainers. Requires Docker — runs in CI and on dev machines.
 *
 * <p>Each test class gets a fresh database (per-class schema via Flyway on
 * a dedicated database) so tests are isolated without cross-contamination.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("redpandadata/redpanda:v23.3.9"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Each test class gets a fresh database on the shared container,
        // so tests are isolated without cross-contamination.
        String db = "test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var conn = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var st = conn.createStatement()) {
            st.execute("CREATE DATABASE \"" + db + "\"");
        } catch (Exception e) {
            throw new IllegalStateException("could not create test database " + db, e);
        }
        String baseUrl = POSTGRES.getJdbcUrl();
        String url = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + db;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Keep background jobs quiet-ish during tests.
        registry.add("ledgerflow.reconciliation.interval-ms", () -> "3600000");
        registry.add("ledgerflow.outbox.poll-ms", () -> "200");
        registry.add("ledgerflow.outbox.max-backoff-seconds", () -> "1");
    }
}
