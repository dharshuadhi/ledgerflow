package com.ledgerflow.ledger;

import com.ledgerflow.common.Money;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of ledger postings.
 *
 * <p>All money movement funnels through {@link #postTransfer} / {@link #postReversal}
 * so the double-entry invariant is enforced in exactly one place, inside the same
 * database transaction that persists the rows.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;

    public LedgerService(AccountRepository accounts, LedgerTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /**
     * Moves {@code amount} from {@code sourceAccountId} to {@code destAccountId}.
     *
     * <p>Writes two postings (DEBIT source, CREDIT destination) plus the parent
     * transaction atomically. The caller's transaction boundary applies; the
     * transfer service owns retries for optimistic-lock conflicts.
     *
     * @return the posted transaction
     * @throws InsufficientFundsException if the source balance is too low
     * @throws AccountNotUsableException  if either account is not ACTIVE
     */
    @Transactional
    public LedgerTransaction postTransfer(UUID sourceAccountId, UUID destAccountId,
                                          Money amount, String idempotencyKey, String description) {
        if (sourceAccountId.equals(destAccountId)) {
            throw new IllegalArgumentException("source and destination accounts must differ");
        }
        if (amount.isZero()) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }

        Account source = accounts.findById(sourceAccountId)
                .orElseThrow(() -> new AccountNotFoundException(sourceAccountId));
        Account dest = accounts.findById(destAccountId)
                .orElseThrow(() -> new AccountNotFoundException(destAccountId));

        requireUsable(source);
        requireUsable(dest);
        requireSameCurrency(source, dest, amount);

        if (source.getBalanceMinorUnits() < amount.minorUnits()) {
            throw new InsufficientFundsException(source.getAccountNumber(), amount);
        }

        LedgerTransaction tx = new LedgerTransaction(TransactionType.TRANSFER, idempotencyKey, description);
        tx.addPosting(new Posting(tx, source, PostingDirection.DEBIT, amount.minorUnits(), amount.currency()));
        tx.addPosting(new Posting(tx, dest, PostingDirection.CREDIT, amount.minorUnits(), amount.currency()));
        tx.validateBalanced();

        // Maintain the cached balances in the same transaction as the postings.
        // Optimistic locking on Account.version turns a lost update into an
        // OptimisticLockException instead of silent corruption.
        source.setBalanceMinorUnits(Math.subtractExact(source.getBalanceMinorUnits(), amount.minorUnits()));
        dest.setBalanceMinorUnits(Math.addExact(dest.getBalanceMinorUnits(), amount.minorUnits()));

        LedgerTransaction saved = transactions.save(tx);
        log.info("posted transfer tx={} {} {} -> {}", saved.getId(), amount,
                source.getAccountNumber(), dest.getAccountNumber());
        return saved;
    }

    /**
     * Reverses a posted transaction with a linked compensating transaction.
     * The original rows are never mutated.
     */
    @Transactional
    public LedgerTransaction postReversal(UUID originalTxId, String reason) {
        LedgerTransaction original = transactions.findById(originalTxId)
                .orElseThrow(() -> new IllegalArgumentException("unknown transaction: " + originalTxId));
        if (original.getStatus() == TransactionStatus.REVERSED) {
            throw new IllegalStateException("transaction already reversed: " + originalTxId);
        }
        LedgerTransaction reversal = new LedgerTransaction(
                TransactionType.ADJUSTMENT, null, "Reversal of " + originalTxId + ": " + reason);
        for (Posting p : original.getPostings()) {
            PostingDirection inverse = p.getDirection() == PostingDirection.DEBIT
                    ? PostingDirection.CREDIT : PostingDirection.DEBIT;
            reversal.addPosting(new Posting(reversal, p.getAccount(),
                    inverse, p.getAmountMinorUnits(), p.getCurrency()));
            Account acct = p.getAccount();
            long delta = inverse == PostingDirection.CREDIT
                    ? p.getAmountMinorUnits() : -p.getAmountMinorUnits();
            acct.setBalanceMinorUnits(Math.addExact(acct.getBalanceMinorUnits(), delta));
        }
        reversal.validateBalanced();
        original.markReversed();
        return transactions.save(reversal);
    }

    private void requireUsable(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotUsableException(account.getAccountNumber(), account.getStatus());
        }
    }

    private void requireSameCurrency(Account source, Account dest, Money amount) {
        if (!source.getCurrency().equals(dest.getCurrency())
                || !source.getCurrency().equals(amount.currency())) {
            throw new IllegalArgumentException(
                    "currency mismatch: accounts %s/%s, amount %s — use the FX conversion flow"
                            .formatted(source.getCurrency(), dest.getCurrency(), amount.currency()));
        }
    }
}
