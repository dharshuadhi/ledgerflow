package com.ledgerflow.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.common.Money;
import com.ledgerflow.events.EventContracts;
import com.ledgerflow.ledger.LedgerService;
import com.ledgerflow.ledger.LedgerTransaction;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transfer attempt, in its own transaction.
 *
 * <p>Kept as a separate bean (rather than a method on {@link TransferService})
 * so the {@code @Transactional} boundary is applied by the Spring proxy even
 * when the transfer service retries the attempt in a loop.
 *
 * <p>The ledger postings and the outbox event commit <strong>atomically</strong>:
 * either both are durable or neither is. There is no window where money moved
 * but no event was emitted, or vice versa.
 */
@Service
public class TransferExecutor {

    private final LedgerService ledger;
    private final com.ledgerflow.outbox.OutboxService outbox;
    private final ObjectMapper mapper;

    public TransferExecutor(LedgerService ledger,
                            com.ledgerflow.outbox.OutboxService outbox,
                            ObjectMapper mapper) {
        this.ledger = ledger;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public TransferService.TransferResult execute(TransferService.TransferCommand cmd,
                                                  Money amount, String idempotencyKey) {
        LedgerTransaction tx = ledger.postTransfer(
                cmd.sourceAccountId(), cmd.destAccountId(), amount,
                idempotencyKey, cmd.description());
        String scenario = cmd.settlementScenario() != null ? cmd.settlementScenario() : "SUCCESS";
        tx.getMetadata().put("settlementScenario", scenario);

        List<EventContracts.PostingPayload> postings = tx.getPostings().stream()
                .map(p -> new EventContracts.PostingPayload(
                        p.getAccount().getId(), p.getDirection().name(),
                        p.getAmountMinorUnits(), p.getCurrency()))
                .toList();
        var event = new EventContracts.TransactionPosted(
                tx.getId(), tx.getType().name(), idempotencyKey,
                Map.copyOf(tx.getMetadata()), postings);
        outbox.stage("ledger-transaction", tx.getId().toString(),
                EventContracts.TransactionPosted.TYPE, 1, writeJson(event));

        return new TransferService.TransferResult(tx.getId(), tx.getStatus().name(),
                amount.minorUnits(), amount.currency(), false);
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }
}
