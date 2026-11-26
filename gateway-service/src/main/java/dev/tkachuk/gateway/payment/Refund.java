package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.common.Currency;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of the refunds table: all or part of a payment's amount, in the payment's currency, sent to the provider that
 * charged it. providerReference is the provider's own id for the refund, when it gives one; failureCode is set when
 * the refund FAILED, nextCheckAt while it is PROCESSING.
 */
public record Refund(
        UUID id,
        UUID paymentId,
        UUID merchantId,
        String idempotencyKey,
        String requestFingerprint,
        long amount,
        Currency currency,
        RefundStatus status,
        String provider,
        @Nullable String providerReference,
        @Nullable String failureCode,
        @Nullable Instant nextCheckAt,
        Instant createdAt,
        Instant updatedAt) {
}
