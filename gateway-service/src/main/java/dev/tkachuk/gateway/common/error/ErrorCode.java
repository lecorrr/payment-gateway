package dev.tkachuk.gateway.common.error;

/** Business rules that can refuse a request; ApiExceptionHandler maps each one to an HTTP status. */
public enum ErrorCode {
    CARD_INVALID,
    CARD_EXPIRED,
    RETURN_URL_INVALID,
    PAYMENT_NOT_FOUND,
    IDEMPOTENCY_KEY_REUSED
}
