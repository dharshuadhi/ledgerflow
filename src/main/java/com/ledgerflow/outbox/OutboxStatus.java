package com.ledgerflow.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    POISON
}
