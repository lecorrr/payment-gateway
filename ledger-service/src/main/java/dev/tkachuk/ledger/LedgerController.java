package dev.tkachuk.ledger;

import dev.tkachuk.ledger.JournalEntry.Kind;
import dev.tkachuk.ledger.LedgerRepository.Balance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The ledger's internal API: what it booked for a payment, and what is owed to a merchant. Amounts are in minor
 * units. It has no authentication: compose publishes it on localhost only, and in Kubernetes no network policy lets
 * anything reach it, while kubectl port-forward does.
 */
@RestController
@RequestMapping("/api/v1")
class LedgerController {

    private final Ledger ledger;

    LedgerController(Ledger ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/payments/{paymentId}")
    PaymentView payment(@PathVariable UUID paymentId) {
        List<JournalEntry> entries = ledger.entriesOfPayment(paymentId);
        if (entries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing is booked for payment " + paymentId);
        }
        return PaymentView.of(paymentId, entries);
    }

    @GetMapping("/merchants/{merchantId}/balances")
    BalancesView balances(@PathVariable UUID merchantId) {
        return new BalancesView(merchantId, ledger.balancesOf(merchantId));
    }

    /** A payment's entries, and what they add up to: paid, less refunded, is net. */
    record PaymentView(UUID paymentId, UUID merchantId, String provider, String currency, long paid, long refunded,
            long net, List<EntryView> entries) {

        static PaymentView of(UUID paymentId, List<JournalEntry> entries) {
            JournalEntry first = entries.getFirst();
            long paid = total(entries, Kind.PAYMENT);
            long refunded = total(entries, Kind.REFUND);
            return new PaymentView(paymentId, first.merchantId(), first.provider(), first.currency(), paid, refunded,
                    paid - refunded, entries.stream().map(EntryView::of).toList());
        }

        private static long total(List<JournalEntry> entries, Kind kind) {
            return entries.stream().filter(entry -> entry.kind() == kind).mapToLong(JournalEntry::amount).sum();
        }
    }

    record EntryView(UUID id, Kind kind, @Nullable UUID refundId, long amount, Instant occurredAt, Instant bookedAt,
            List<JournalEntry.Posting> postings) {

        static EntryView of(JournalEntry entry) {
            return new EntryView(entry.id(), entry.kind(), entry.refundId(), entry.amount(), entry.occurredAt(),
                    entry.bookedAt(), entry.postings());
        }
    }

    record BalancesView(UUID merchantId, List<Balance> balances) {
    }
}
