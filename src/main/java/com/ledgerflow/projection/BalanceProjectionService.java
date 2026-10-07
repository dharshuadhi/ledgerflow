package com.ledgerflow.projection;

import com.ledgerflow.events.EventContracts;
import com.ledgerflow.ledger.PostingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the balance read model.
 *
 * <p>Three operations, in order of trust:
 * <ol>
 *   <li>{@link #apply} — folds one event's postings into the projection.</li>
 *   <li>{@link #rebuildFromPostings} — drops the projection and recomputes it
 *       from the authoritative posting history. This is the disaster-recovery
 *       path: a corrupted or deleted read model is fully recoverable.</li>
 *   <li>{@link #findInconsistencies} — compares projection vs postings-derived
 *       balances and reports every divergence.</li>
 * </ol>
 */
@Service
public class BalanceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(BalanceProjectionService.class);

    private final BalanceProjectionRepository projections;
    private final PostingRepository postings;
    private final ProcessedEventRepository processedEvents;

    public BalanceProjectionService(BalanceProjectionRepository projections,
                                    PostingRepository postings,
                                    ProcessedEventRepository processedEvents) {
        this.projections = projections;
        this.postings = postings;
        this.processedEvents = processedEvents;
    }

    /**
     * Applies one {@code ledger.transaction.posted} event. Idempotent: already
     * processed event ids are skipped by the caller.
     */
    @Transactional
    public void apply(EventContracts.TransactionPosted event) {
        for (EventContracts.PostingPayload p : event.postings()) {
            long delta = "CREDIT".equals(p.direction()) ? p.amountMinorUnits() : -p.amountMinorUnits();
            projections.applyDelta(p.accountId(), p.currency(), delta, event.eventId());
        }
    }

    @Transactional
    public void markProcessed(UUID eventId, String consumer) {
        processedEvents.save(new ProcessedEvent(eventId, consumer));
    }

    @Transactional(readOnly = true)
    public boolean alreadyProcessed(UUID eventId) {
        return processedEvents.existsById(eventId);
    }

    @Transactional(readOnly = true)
    public Optional<Long> projectedBalance(UUID accountId) {
        return projections.findById(accountId).map(BalanceProjection::getBalanceMinorUnits);
    }

    /**
     * Rebuilds the entire projection from the authoritative posting history.
     * Used after corruption, and exercised by tests to prove the read model
     * carries no unique state.
     *
     * @return number of account balances rebuilt
     */
    @Transactional
    public int rebuildFromPostings() {
        projections.deleteAll();
        List<Object[]> rows = postings.netPerAccount();
        for (Object[] row : rows) {
            UUID accountId = (UUID) row[0];
            String currency = (String) row[1];
            long balance = ((Number) row[2]).longValue();
            projections.save(new BalanceProjection(accountId, currency, balance, null));
        }
        log.info("rebuilt {} balance projections from postings", rows.size());
        return rows.size();
    }

    /** Returns human-readable descriptions of every projection/postings divergence. */
    @Transactional(readOnly = true)
    public List<String> findInconsistencies() {
        List<String> problems = new ArrayList<>();
        for (BalanceProjection p : projections.findAll()) {
            long authoritative = postings.authoritativeBalance(p.getAccountId(), p.getCurrency());
            if (authoritative != p.getBalanceMinorUnits()) {
                problems.add("account %s: projection=%d postings=%d".formatted(
                        p.getAccountId(), p.getBalanceMinorUnits(), authoritative));
            }
        }
        return problems;
    }
}
