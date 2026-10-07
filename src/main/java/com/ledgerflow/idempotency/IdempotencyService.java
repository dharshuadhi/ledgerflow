package com.ledgerflow.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency coordinator for money-moving APIs.
 *
 * <p>Behavior contract (see {@code docs/idempotency.md}):
 * <ul>
 *   <li>Same key + same request → the stored response is replayed (no re-execution).</li>
 *   <li>Same key + different request → {@code 422}; a key identifies one request.</li>
 *   <li>Same key while the first request is still running → brief wait, then {@code 409}.</li>
 *   <li>Failed first attempt → the failure response is replayed deterministically.</li>
 * </ul>
 *
 * <p>All state transitions run in {@code REQUIRES_NEW} transactions so they survive
 * the rollback of the business transaction they guard.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration IN_FLIGHT_WAIT = Duration.ofSeconds(4);
    private static final long POLL_MS = 100;

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    /** Result of attempting to claim a key. */
    public sealed interface Claim permits Claim.Owned, Claim.Replay {
        record Owned() implements Claim {}
        record Replay(int responseStatus, String responseBody) implements Claim {}
    }

    /**
     * Tries to claim {@code key} for this request.
     *
     * @return {@link Claim.Owned} if this call won the race and must execute,
     *         {@link Claim.Replay} if a previous attempt already finished
     * @throws IdempotencyKeyReuseException if the key was used with a different request
     * @throws ConcurrentRequestException  if the first request is still in flight
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String key, String requestHash, Duration ttl) {
        try {
            repository.saveAndFlush(new IdempotencyRecord(key, requestHash, Instant.now().plus(ttl)));
            log.debug("idempotency key claimed: {}", key);
            return new Claim.Owned();
        } catch (DataIntegrityViolationException alreadyExists) {
            return handleConflict(key, requestHash);
        }
    }

    private Claim handleConflict(String key, String requestHash) {
        Optional<IdempotencyRecord> existing = repository.findById(key);
        if (existing.isEmpty()) {
            // Lost a race with an expiring row; let the caller retry the claim.
            throw new ConcurrentRequestException(key);
        }
        IdempotencyRecord record = existing.get();
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException(key);
        }
        return switch (record.getStatus()) {
            case COMPLETED, FAILED -> {
                log.debug("idempotency replay for key {}", key);
                yield new Claim.Replay(record.getResponseStatus(), record.getResponseBody());
            }
            case IN_PROGRESS -> {
                IdempotencyRecord settled = awaitSettlement(key);
                if (settled != null
                        && (settled.getStatus() == IdempotencyStatus.COMPLETED
                            || settled.getStatus() == IdempotencyStatus.FAILED)) {
                    yield new Claim.Replay(settled.getResponseStatus(), settled.getResponseBody());
                }
                throw new ConcurrentRequestException(key);
            }
        };
    }

    /** Waits briefly for an in-flight request to finish; returns null on timeout. */
    private IdempotencyRecord awaitSettlement(String key) {
        Instant deadline = Instant.now().plus(IN_FLIGHT_WAIT);
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            Optional<IdempotencyRecord> current = repository.findById(key);
            if (current.isPresent() && current.get().getStatus() != IdempotencyStatus.IN_PROGRESS) {
                return current.get();
            }
        }
        return null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, int responseStatus, String responseBody) {
        repository.findById(key).ifPresent(r -> r.complete(responseStatus, responseBody));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String key, int responseStatus, String responseBody) {
        repository.findById(key).ifPresent(r -> r.fail(responseStatus, responseBody));
    }

    /** SHA-256 over canonical request JSON; same logical request → same hash. */
    public static String hash(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
