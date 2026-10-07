package com.ledgerflow.ledger;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID accountId) {
        super("account not found: " + accountId);
    }

    public AccountNotFoundException(String accountNumber) {
        super("account not found: " + accountNumber);
    }
}
