package com.ledgerflow.ledger;

import com.ledgerflow.common.Money;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String accountNumber, Money amount) {
        super("insufficient funds in account %s for %s".formatted(accountNumber, amount));
    }
}
