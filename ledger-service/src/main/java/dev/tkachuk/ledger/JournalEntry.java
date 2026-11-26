package dev.tkachuk.ledger;

import dev.tkachuk.ledger.LedgerEvent.PaymentSucceeded;
import dev.tkachuk.ledger.LedgerEvent.RefundSucceeded;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A journal entry: one succeeded payment or refund, as balanced postings. A payment owes its amount to the merchant
 * (credit merchant:id) and is owed it by the provider that charged the card (debit provider:name); a refund takes
 * its amount back the other way. The entry's id is its event's.
 */
record JournalEntry(UUID id, Kind kind, UUID merchantId, UUID paymentId, @Nullable UUID refundId, String provider,
        String currency, long amount, Instant occurredAt, Instant bookedAt, List<Posting> postings) {

    enum Kind {
        PAYMENT,
        REFUND
    }

    /** One side of an entry, in minor units: positive is a debit, negative a credit. */
    record Posting(String account, long amount) {

        Posting {
            if (amount == 0) {
                throw new IllegalArgumentException("A posting to " + account + " moves nothing");
            }
        }
    }

    JournalEntry {
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("Entry " + id + " needs a debit and a credit, has " + postings);
        }
        long sum = postings.stream().mapToLong(Posting::amount).sum();
        if (sum != 0) {
            throw new IllegalArgumentException("Entry " + id + " doesn't balance: its postings add up to " + sum);
        }
        if ((kind == Kind.REFUND) != (refundId != null)) {
            throw new IllegalArgumentException("Entry " + id + " is a " + kind + " with refund " + refundId);
        }
    }

    static JournalEntry of(PaymentSucceeded payment, Instant bookedAt) {
        return new JournalEntry(payment.eventId(), Kind.PAYMENT, payment.merchantId(), payment.paymentId(), null,
                payment.provider(), payment.currency(), payment.amount(), payment.occurredAt(), bookedAt, List.of(
                        new Posting(providerAccount(payment.provider()), payment.amount()),
                        new Posting(merchantAccount(payment.merchantId()), -payment.amount())));
    }

    static JournalEntry of(RefundSucceeded refund, Instant bookedAt) {
        return new JournalEntry(refund.eventId(), Kind.REFUND, refund.merchantId(), refund.paymentId(),
                refund.refundId(), refund.provider(), refund.currency(), refund.amount(), refund.occurredAt(),
                bookedAt, List.of(
                        new Posting(merchantAccount(refund.merchantId()), refund.amount()),
                        new Posting(providerAccount(refund.provider()), -refund.amount())));
    }

    static String merchantAccount(UUID merchantId) {
        return "merchant:" + merchantId;
    }

    static String providerAccount(String provider) {
        return "provider:" + provider;
    }
}
