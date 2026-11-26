package dev.tkachuk.ledger;

import dev.tkachuk.ledger.LedgerEvent.NotBooked;
import dev.tkachuk.ledger.LedgerEvent.PaymentSucceeded;
import dev.tkachuk.ledger.LedgerEvent.RefundSucceeded;
import dev.tkachuk.ledger.LedgerRepository.Balance;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes each event once: an event is noted as taken and booked in one transaction, so one delivered again finds
 * itself taken and changes nothing. That makes the topic's at-least-once delivery exactly once for the ledger.
 */
@Service
class Ledger {

    enum Outcome {
        BOOKED,
        NOT_BOOKED,
        DUPLICATE
    }

    private final LedgerRepository repository;
    private final Clock clock;

    Ledger(LedgerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Outcome take(LedgerEvent event, RecordSource source) {
        Instant now = clock.instant();
        if (!repository.markProcessed(event, source, now)) {
            return Outcome.DUPLICATE;
        }
        return switch (event) {
            case PaymentSucceeded payment -> book(JournalEntry.of(payment, now));
            case RefundSucceeded refund -> book(JournalEntry.of(refund, now));
            case NotBooked ignored -> Outcome.NOT_BOOKED;
        };
    }

    @Transactional(readOnly = true)
    public List<JournalEntry> entriesOfPayment(UUID paymentId) {
        return repository.entriesOfPayment(paymentId);
    }

    @Transactional(readOnly = true)
    public List<Balance> balancesOf(UUID merchantId) {
        return repository.balancesOf(merchantId);
    }

    private Outcome book(JournalEntry entry) {
        repository.book(entry);
        return Outcome.BOOKED;
    }
}
