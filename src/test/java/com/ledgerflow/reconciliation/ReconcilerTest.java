package com.ledgerflow.reconciliation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ledgerflow.ledger.*;
import com.ledgerflow.outbox.OutboxService;
import com.ledgerflow.settlement.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Reconciler logic with mocked repositories — no database needed. */
@ExtendWith(MockitoExtension.class)
class ReconcilerTest {

    @Mock ReconciliationRunRepository runRepo;
    @Mock ReconciliationMismatchRepository mismatchRepo;
    @Mock LedgerTransactionRepository txRepo;
    @Mock SettlementRepository settlementRepo;
    @Mock OutboxService outbox;

    Reconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new Reconciler(runRepo, mismatchRepo, txRepo, settlementRepo,
                outbox, new ObjectMapper().registerModule(new JavaTimeModule()),
                new SimpleMeterRegistry(), 120);
        lenient().when(runRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(mismatchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private LedgerTransaction postedTx(String key, long amountMinor) {
        var tx = new LedgerTransaction(TransactionType.TRANSFER, key, "test transfer");
        var a = new Account("A-" + key, "alice", "USD");
        var b = new Account("B-" + key, "bob", "USD");
        tx.addPosting(new Posting(tx, a, PostingDirection.DEBIT, amountMinor, "USD"));
        tx.addPosting(new Posting(tx, b, PostingDirection.CREDIT, amountMinor, "USD"));
        return tx; // constructor marks POSTED
    }

    private void givenPosted(LedgerTransaction tx) {
        when(txRepo.findByStatusOrderByCreatedAtDesc(
                eq(TransactionStatus.POSTED), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(tx)));
    }

    @Test
    void settledTransferProducesNoMismatch() {
        var tx = postedTx("k1", 1000);
        var s = new Settlement(tx, 1000, "USD", SettlementScenario.SUCCESS);
        s.markSettled("prov-1", "evt-1");
        givenPosted(tx);

        when(settlementRepo.findByTransactionId(tx.getId())).thenReturn(Optional.of(s));

        var run = reconciler.run();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.COMPLETED);
        verify(mismatchRepo, never()).save(any());
        verify(outbox, never()).stage(any(), any(), any(), anyInt(), any());
    }

    @Test
    void missingSettlementIsReported() {
        var tx = postedTx("k2", 500);
        givenPosted(tx);

        when(settlementRepo.findByTransactionId(tx.getId())).thenReturn(Optional.empty());
        when(mismatchRepo.findOpen(any(), any(), any())).thenReturn(List.of());

        reconciler.run();

        var captor = ArgumentCaptor.forClass(ReconciliationMismatch.class);
        verify(mismatchRepo).save(captor.capture());
        assertThat(captor.getValue().getMismatchType()).isEqualTo(MismatchType.MISSING_SETTLEMENT);
        verify(outbox).stage(eq("reconciliation"), any(), any(), eq(1), any());
    }

    @Test
    void amountMismatchIsReported() {
        var tx = postedTx("k3", 1000);
        var s = new Settlement(tx, 999, "USD", SettlementScenario.SUCCESS); // provider short-settled
        s.markSettled("prov-3", "evt-3");
        givenPosted(tx);

        when(settlementRepo.findByTransactionId(tx.getId())).thenReturn(Optional.of(s));
        when(mismatchRepo.findOpen(any(), any(), any())).thenReturn(List.of());

        reconciler.run();

        var captor = ArgumentCaptor.forClass(ReconciliationMismatch.class);
        verify(mismatchRepo).save(captor.capture());
        assertThat(captor.getValue().getMismatchType()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
    }

    @Test
    void openMismatchIsNotDuplicatedOnRerun() {
        var tx = postedTx("k4", 500);
        givenPosted(tx);

        when(settlementRepo.findByTransactionId(tx.getId())).thenReturn(Optional.empty());
        when(mismatchRepo.findOpen(any(), any(), any()))
                .thenReturn(List.of(new ReconciliationMismatch(
                        ReconciliationRun.start(), MismatchType.MISSING_SETTLEMENT, tx, null, "{}")));

        reconciler.run();

        verify(mismatchRepo, never()).save(any());
        verify(outbox, never()).stage(any(), any(), any(), anyInt(), any());
    }
}
