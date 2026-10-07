package com.ledgerflow.settlement;

/** Webhook signature missing or invalid. Maps to HTTP 401. */
public class WebhookSignatureException extends RuntimeException {
    public WebhookSignatureException() {
        super("invalid or missing webhook signature");
    }
}
