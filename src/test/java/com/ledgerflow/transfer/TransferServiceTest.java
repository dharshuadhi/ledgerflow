package com.ledgerflow.transfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ledgerflow.common.Money;
import com.ledgerflow.idempotency.IdempotencyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Transfer orchestration with mocked collaborators — no database needed. */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock TransferExecutor executor;
    @Mock IdempotencyService idempotency;

    TransferService service;
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        service = new TransferService(executor, idempotency, mapper, new SimpleMeterRegistry());
    }

    private TransferService.TransferCommand cmd(String key) {
        return new TransferService.TransferCommand(
                UUID.randomUUID(), UUID.randomUUID(), 2500, "USD",
                "test", key, "SUCCESS");
    }

    private TransferService.TransferResult result(boolean replayed) {
        return new TransferService.TransferResult(UUID.randomUUID(), "POSTED", 2500, "USD", replayed);
    }

    @Test
    void happyPathExecutesAndCompletesIdempotency() {
        var c = cmd("k-1");
        var r = result(false);
        when(idempotency.claim(eq("k-1"), any(), any(Duration.class)))
                .thenReturn(new IdempotencyService.Claim.Owned());
        when(executor.execute(eq(c), any(Money.class), eq("k-1"))).thenReturn(r);

        var out = service.transfer(c);

        assertThat(out).isEqualTo(r);
        assertThat(out.replayed()).isFalse();
        verify(idempotency).complete(eq("k-1"), eq(201), any());
    }

    @Test
    void replayReturnsStoredResultWithoutExecuting() {
        var c = cmd("k-2");
        var original = result(false);
        String body;
        try {
            body = mapper.writeValueAsString(original);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(idempotency.claim(eq("k-2"), any(), any(Duration.class)))
                .thenReturn(new IdempotencyService.Claim.Replay(201, body));

        var out = service.transfer(c);

        assertThat(out.transactionId()).isEqualTo(original.transactionId());
        assertThat(out.replayed()).isTrue();
        verify(executor, never()).execute(any(), any(), any());
    }

    @Test
    void replayedFailureRethrowsInsteadOfExecuting() {
        var c = cmd("k-3");
        when(idempotency.claim(eq("k-3"), any(), any(Duration.class)))
                .thenReturn(new IdempotencyService.Claim.Replay(
                        422, "{\"error\":\"insufficient funds\"}"));

        assertThatThrownBy(() -> service.transfer(c))
                .isInstanceOf(RuntimeException.class);
        verify(executor, never()).execute(any(), any(), any());
    }

    @Test
    void optimisticLockConflictRetriesThenSucceeds() {
        var c = cmd("k-4");
        var r = result(false);
        when(idempotency.claim(eq("k-4"), any(), any(Duration.class)))
                .thenReturn(new IdempotencyService.Claim.Owned());
        when(executor.execute(eq(c), any(Money.class), eq("k-4")))
                .thenThrow(new ObjectOptimisticLockingFailureException("conflict", new RuntimeException()))
                .thenReturn(r);

        var out = service.transfer(c);

        assertThat(out).isEqualTo(r);
        verify(executor, times(2)).execute(eq(c), any(Money.class), eq("k-4"));
        verify(idempotency).complete(eq("k-4"), eq(201), any());
    }

    @Test
    void executionFailureIsRecordedAsFailed() {
        var c = cmd("k-5");
        when(idempotency.claim(eq("k-5"), any(), any(Duration.class)))
                .thenReturn(new IdempotencyService.Claim.Owned());
        when(executor.execute(eq(c), any(Money.class), eq("k-5")))
                .thenThrow(new IllegalArgumentException("bad amount"));

        assertThatThrownBy(() -> service.transfer(c))
                .isInstanceOf(IllegalArgumentException.class);
        verify(idempotency).fail(eq("k-5"), eq(422), any());
    }
}
