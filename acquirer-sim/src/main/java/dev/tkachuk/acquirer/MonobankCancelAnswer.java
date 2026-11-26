package dev.tkachuk.acquirer;

import java.time.Instant;

/** What monobank answers to a cancel: the refund's status as cancelList shows it, and its times. */
record MonobankCancelAnswer(String status, Instant createdDate, Instant modifiedDate) {

    static MonobankCancelAnswer of(Refund refund) {
        MonobankInvoice.CancelItem item = MonobankInvoice.CancelItem.of(refund);
        return new MonobankCancelAnswer(item.status(), item.createdDate(), item.modifiedDate());
    }
}
