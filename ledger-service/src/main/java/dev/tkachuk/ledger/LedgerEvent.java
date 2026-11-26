package dev.tkachuk.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * An event of the payment-events topic as the ledger needs it, read and checked by EventReader. Only a payment
 * that succeeded and a refund that succeeded change the ledger; every other event is only noted as taken.
 */
sealed interface LedgerEvent {

    /** The CloudEvents id: the same event delivered again has the same one. */
    UUID eventId();

    String type();

    UUID paymentId();

    /** A payment of amount, in minor units of currency, that provider charged for merchant. */
    record PaymentSucceeded(UUID eventId, UUID paymentId, UUID merchantId, String provider, String currency,
            long amount, Instant occurredAt) implements LedgerEvent {

        @Override
        public String type() {
            return EventReader.PAYMENT_SUCCEEDED;
        }
    }

    /** A refund of amount, in minor units of currency, that provider gave back from the merchant's payment. */
    record RefundSucceeded(UUID eventId, UUID refundId, UUID paymentId, UUID merchantId, String provider,
            String currency, long amount, Instant occurredAt) implements LedgerEvent {

        @Override
        public String type() {
            return EventReader.REFUND_SUCCEEDED;
        }
    }

    /** Any other event: a payment declined or waiting for 3-D Secure, a refund that failed, a type added later. */
    record NotBooked(UUID eventId, String type, UUID paymentId) implements LedgerEvent {
    }
}
