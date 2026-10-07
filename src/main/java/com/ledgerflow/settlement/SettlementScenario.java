package com.ledgerflow.settlement;

/**
 * Deterministic provider behaviors for the settlement simulator.
 *
 * <p>This is a <strong>test hook</strong>, not a real bank integration: it lets
 * demos, chaos scenarios, and integration tests exercise every settlement
 * outcome without pretending to move real money. Chosen per transfer via the
 * {@code settlementScenario} request field (default {@code SUCCESS}).
 */
public enum SettlementScenario {
    /** Provider confirms settlement promptly. */
    SUCCESS,
    /** Provider rejects (e.g. invalid beneficiary). Terminal. */
    FAIL,
    /** Provider never responds; settlement stays PROCESSING until reconciled as late. */
    TIMEOUT,
    /** Provider confirms after a delay (slow rail). */
    DELAYED,
    /** Provider delivers the same success callback twice; second must be a no-op. */
    DUPLICATE_CALLBACK,
    /** Provider is down; attempts fail retryably, settlement stays PROCESSING. */
    PROVIDER_OUTAGE
}
