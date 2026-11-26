package dev.tkachuk.gateway.event;

import dev.tkachuk.gateway.common.Currency;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The data of a payment's event on the payment-events topic (payment.requires_action, .succeeded, .declined,
 * .failed), as docs/payment-events.yaml describes it: the payment as it is after the change, with its merchant and
 * provider, which other services need and a merchant's webhook leaves out. No card data but none the API shows.
 * Amounts are in minor units.
 */
public record PaymentEventData(
        UUID paymentId,
        UUID merchantId,
        String orderId,
        String status,
        long amount,
        Currency currency,
        String provider,
        @Nullable String providerReference,
        @Nullable String declineCode,
        long refundedAmount,
        Instant occurredAt) {
}
