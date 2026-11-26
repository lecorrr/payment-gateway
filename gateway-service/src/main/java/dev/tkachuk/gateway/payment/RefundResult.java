package dev.tkachuk.gateway.payment;

/** replayed: an earlier request with the same Idempotency-Key created the refund. */
public record RefundResult(Refund refund, boolean replayed) {
}
