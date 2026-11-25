package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.CardBrand;
import dev.tkachuk.gateway.common.Currency;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of the payments table. Of the card only the brand, the first six and the last four digits are kept, which
 * PCI DSS allows to be shown; nextCheckAt is set while the payment is PROCESSING.
 */
public record Payment(
        UUID id,
        UUID merchantId,
        String idempotencyKey,
        String requestFingerprint,
        String orderId,
        long amount,
        Currency currency,
        @Nullable String description,
        PaymentStatus status,
        CardBrand cardBrand,
        String cardBin,
        String cardLast4,
        String provider,
        @Nullable String providerReference,
        @Nullable String declineCode,
        @Nullable Instant nextCheckAt,
        Instant createdAt,
        Instant updatedAt) {
}
