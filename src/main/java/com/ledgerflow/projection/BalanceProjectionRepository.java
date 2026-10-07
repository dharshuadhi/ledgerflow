package com.ledgerflow.projection;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BalanceProjectionRepository extends JpaRepository<BalanceProjection, UUID> {

    /**
     * Atomic upsert of a posting delta. The single statement keeps concurrent
     * consumer threads from interleaving a read-modify-write.
     */
    @Modifying
    @Query(value = """
            insert into balance_projections (account_id, currency, balance_minor_units, last_event_id, updated_at)
            values (:accountId, :currency, :delta, :eventId, now())
            on conflict (account_id) do update set
                balance_minor_units = balance_projections.balance_minor_units + excluded.balance_minor_units,
                last_event_id = excluded.last_event_id,
                updated_at = now()
            """, nativeQuery = true)
    void applyDelta(UUID accountId, String currency, long delta, UUID eventId);
}
