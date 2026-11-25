package dev.tkachuk.gateway.payment;

/** replayed: an earlier request with the same Idempotency-Key created the payment. */
public record PaymentResult(Payment payment, boolean replayed) {
}
