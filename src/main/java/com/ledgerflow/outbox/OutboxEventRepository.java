package com.ledgerflow.outbox;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of due events. {@code FOR UPDATE SKIP LOCKED} lets several
     * relay instances share work without two threads taking the same row. Locks
     * are released as soon as the claiming transaction commits — delivery
     * re-locks each row individually (see {@link #lockById}).
     */
    @Query(value = """
            select * from outbox_events
            where status = 'PENDING' and next_attempt_at <= :now
            order by created_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> claimDueBatch(Instant now, int limit);

    /** Exclusive row lock for delivery; a racing relay blocks here, then sees the new status. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> lockById(UUID id);

    long countByStatus(OutboxStatus status);
}
