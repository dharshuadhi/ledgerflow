package com.ledgerflow.ledger;

public class AccountNotUsableException extends RuntimeException {
    public AccountNotUsableException(String accountNumber, AccountStatus status) {
        super("account %s is not usable (status=%s)".formatted(accountNumber, status));
    }
}
