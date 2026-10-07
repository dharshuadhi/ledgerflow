package com.ledgerflow.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.common.Money;
import com.ledgerflow.events.EventContracts;
import com.ledgerflow.idempotency.ConcurrentRequestException;
import com.ledgerflow.idempotency.IdempotencyKeyReuseException;
import com.ledgerflow.idempotency.IdempotencyService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Executes money transfers with end-to-end safety:
 *
 * <ol>
 *   <li><strong>Idempotency</strong> — the client's {@code Idempotency-Key} makes
 *       retries safe (replay, never re-execution).</li>
 *   <li><strong>Atomicity</strong> — ledger postings and the outbox event commit
 *       in one database transaction.</li>
 *   <li><strong>Concurrency</strong> — optimistic-lock conflicts on hot accounts
 *       are retried with jittered backoff instead of failing the client.</li>
 * </ol>
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final int MAX_ATTEMPTS = 4;

    private final TransferExecutor executor;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    private final Timer transferTimer;

    public TransferService(TransferExecutor executor,
                           IdempotencyService idempotency,
                           ObjectMapper mapper, MeterRegistry meters) {
        this.executor = executor;
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.transferTimer = Timer.builder("ledgerflow.transfer.duration")
                .description("End-to-end transfer execution time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    /** Idempotent transfer command. */
    public record TransferCommand(
            UUID sourceAccountId,
            UUID destAccountId,
            long amountMinorUnits,
            String currency,
            String description,
            String idempotencyKey,
            String settlementScenario) {
    }

    /** Result returned to the client (and replayed on retry). */
    public record TransferResult(
            UUID transactionId,
            String status,
            long amountMinorUnits,
            String currency,
            boolean replayed) {
    }

    public TransferResult transfer(TransferCommand cmd) {
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()
                || cmd.idempotencyKey().length() > 64) {
            throw new IllegalArgumentException("Idempotency-Key must be 1..64 characters");
        }
        Money amount = Money.of(cmd.amountMinorUnits(), cmd.currency());
        String requestHash = hashRequest(cmd);

        var claim = idempotency.claim(cmd.idempotencyKey(), requestHash, IDEMPOTENCY_TTL);
        if (claim instanceof IdempotencyService.Claim.Replay replay) {
            log.info("replaying transfer for idempotency key {}", cmd.idempotencyKey());
            if (replay.responseStatus() >= 400) {
                throw replayedFailure(replay);
            }
            return readResult(replay.responseBody(), true);
        }

        // We own the key: execute, with bounded retries on optimistic-lock conflicts.
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                TransferResult result = transferTimer.record(() ->
                        executor.execute(cmd, amount, cmd.idempotencyKey()));
                idempotency.complete(cmd.idempotencyKey(), 201, writeResult(result));
                return result;
            } catch (OptimisticLockingFailureException conflict) {
                if (attempt >= MAX_ATTEMPTS) {
                    idempotency.fail(cmd.idempotencyKey(), 409, errorJson("concurrent modification, please retry"));
                    throw new ConcurrentRequestException(cmd.idempotencyKey());
                }
                backoff(attempt);
                log.info("optimistic lock conflict on transfer {} (attempt {}), retrying",
                        cmd.idempotencyKey(), attempt);
            } catch (RuntimeException e) {
                int status = statusFor(e);
                idempotency.fail(cmd.idempotencyKey(), status, errorJson(e.getMessage()));
                throw e;
            }
        }
    }

    private String hashRequest(TransferCommand cmd) {
        // Canonical form: sorted keys, so equivalent JSON hashes identically.
        Map<String, Object> canonical = new TreeMap<>(Map.of(
                "source", cmd.sourceAccountId().toString(),
                "dest", cmd.destAccountId().toString(),
                "amount", cmd.amountMinorUnits(),
                "currency", cmd.currency(),
                "description", cmd.description() == null ? "" : cmd.description()));
        return IdempotencyService.hash(writeJson(canonical));
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(25L << attempt, 50L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during transfer retry", e);
        }
    }

    private int statusFor(RuntimeException e) {
        return switch (e.getClass().getSimpleName()) {
            case "InsufficientFundsException", "AccountNotUsableException",
                 "IllegalArgumentException", "IdempotencyKeyReuseException" -> 422;
            case "AccountNotFoundException" -> 404;
            default -> 500;
        };
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    private String writeResult(TransferResult r) {
        return writeJson(r);
    }

    private TransferResult readResult(String json, boolean replayed) {
        try {
            TransferResult r = mapper.readValue(json, TransferResult.class);
            return new TransferResult(r.transactionId(), r.status(),
                    r.amountMinorUnits(), r.currency(), replayed);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read stored idempotent response", e);
        }
    }

    private String errorJson(String message) {
        return writeJson(Map.of("error", message == null ? "transfer failed" : message));
    }

    /** Rebuilds the original failure from the stored idempotent response. */
    private RuntimeException replayedFailure(IdempotencyService.Claim.Replay replay) {
        String message = "transfer failed";
        try {
            var node = mapper.readTree(replay.responseBody());
            message = node.path("error").asText(message);
        } catch (Exception ignored) {
        }
        return new com.ledgerflow.idempotency.ReplayedFailureException(
                replay.responseStatus(), message);
    }
}
