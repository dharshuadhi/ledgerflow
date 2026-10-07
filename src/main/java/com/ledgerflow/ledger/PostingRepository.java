package com.ledgerflow.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    Page<Posting> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    List<Posting> findByTransactionId(UUID transactionId);

    /**
     * Authoritative balance: net of postings per account. A CREDIT increases the
     * account holder's balance, a DEBIT decreases it — so balance equals total
     * credits minus total debits, derived purely from the posting history.
     */
    @Query("""
            select coalesce(sum(case when p.direction = 'CREDIT' then p.amountMinorUnits
                                    else -p.amountMinorUnits end), 0)
            from Posting p where p.account.id = :accountId and p.currency = :currency
            """)
    long authoritativeBalance(UUID accountId, String currency);

    /** Global invariant probe: net across ALL postings must be zero per currency. */
    @Query("""
            select p.currency, sum(case when p.direction = 'DEBIT' then p.amountMinorUnits
                                       else -p.amountMinorUnits end)
            from Posting p group by p.currency
            """)
    List<Object[]> netPerCurrency();

    /** Per-account net for projection rebuilds: (accountId, currency, net). */
    @Query("""
            select p.account.id, p.currency, sum(case when p.direction = 'CREDIT' then p.amountMinorUnits
                                                     else -p.amountMinorUnits end)
            from Posting p group by p.account.id, p.currency
            """)
    List<Object[]> netPerAccount();
}
