package com.nexuspay.common.error;

/**
 * Stable, machine-readable error codes.
 * <p>
 * These appear in API responses and in support tickets, so they are part of the
 * public contract: rename one and you break a client's error handling. There is
 * deliberately no HTTP status here — mapping a domain failure onto a status code
 * is a web concern, and lives in the exception handler. Keeping it out is what
 * lets the domain be tested without a servlet container.
 */
public enum ErrorCode {

    // Validation and input
    VALIDATION_FAILED,
    CURRENCY_MISMATCH,
    UNSUPPORTED_CURRENCY,

    // Lookup
    ENTITY_NOT_FOUND,

    // Business rules
    BUSINESS_RULE_VIOLATION,
    INVALID_STATE_TRANSITION,
    CARD_NOT_USABLE,
    AMOUNT_EXCEEDS_LIMIT,

    // Idempotency (Phase 1.5)
    IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY,
    IDEMPOTENCY_REQUEST_IN_PROGRESS,

    // Catch-all
    INTERNAL_ERROR
}
