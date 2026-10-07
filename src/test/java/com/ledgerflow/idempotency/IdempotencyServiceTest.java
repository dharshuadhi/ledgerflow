package com.ledgerflow.idempotency;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Idempotency claim protocol with a mocked repository — no database needed. */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock IdempotencyRepository repository;
    IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository);
    }

    private IdempotencyRecord completed(String key, String hash) {
        var r = new IdempotencyRecord(key, hash, Instant.now().plus(Duration.ofHours(1)));
        r.complete(201, "{\"ok\":true}");
        return r;
    }

    @Test
    void firstClaimWins() {
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        var claim = service.claim("key-1", "hash-a", Duration.ofHours(24));

        assertThat(claim).isInstanceOf(IdempotencyService.Claim.Owned.class);
    }

    @Test
    void sameKeySameHashReplaysStoredResponse() {
        var existing = completed("key-2", "hash-a");
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));
        when(repository.findById("key-2")).thenReturn(Optional.of(existing));

        var claim = service.claim("key-2", "hash-a", Duration.ofHours(24));

        assertThat(claim).isInstanceOf(IdempotencyService.Claim.Replay.class);
        var replay = (IdempotencyService.Claim.Replay) claim;
        assertThat(replay.responseStatus()).isEqualTo(201);
        assertThat(replay.responseBody()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void sameKeyDifferentHashIsRejected() {
        var existing = completed("key-3", "hash-a");
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));
        when(repository.findById("key-3")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.claim("key-3", "hash-B-DIFFERENT", Duration.ofHours(24)))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void inFlightKeyThrowsConcurrentRequest() {
        var inFlight = new IdempotencyRecord("key-4", "hash-a",
                Instant.now().plus(Duration.ofHours(1)));
        // saveAndFlush throws (duplicate), findById returns IN_PROGRESS record forever
        // -> awaitSettlement polls until the deadline, then ConcurrentRequestException.
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));
        when(repository.findById("key-4")).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> service.claim("key-4", "hash-a", Duration.ofHours(24)))
                .isInstanceOf(ConcurrentRequestException.class);
    }

    @Test
    void hashIsDeterministic() {
        assertThat(IdempotencyService.hash("{\"a\":1}"))
                .isEqualTo(IdempotencyService.hash("{\"a\":1}"));
        assertThat(IdempotencyService.hash("{\"a\":1}"))
                .isNotEqualTo(IdempotencyService.hash("{\"a\":2}"));
    }
}
