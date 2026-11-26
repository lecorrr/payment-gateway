package dev.tkachuk.gateway.event;

import dev.tkachuk.gateway.common.Currency;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The data of a refund's event on the payment-events topic (refund.succeeded, refund.failed), as
 * docs/payment-events.yaml describes it: the refund as it ended, with the payment, merchant and provider it belongs
 * to. Its Kafka key is the payment's id, so it follows the payment's own events. Amounts are in minor units.
 */
public record RefundEventData(
        UUID refundId,
        UUID paymentId,
        UUID merchantId,
        String status,
        long amount,
        Currency currency,
        String provider,
        @Nullable String providerReference,
        @Nullable String failureCode,
        Instant occurredAt) {
}
