package dev.tkachuk.acquirer;

import java.time.Instant;
import java.util.List;

/** GET /api/merchant/statement: the invoices of a period, the newest first, in monobank's names. */
record MonobankStatement(List<Item> list) {

    record Item(String invoiceId, String status, long amount, int ccy, Instant date, String reference) {

        static Item of(Charge charge) {
            MonobankInvoice invoice = MonobankInvoice.of(charge, List.of());
            return new Item(invoice.invoiceId(), invoice.status(), invoice.amount(), invoice.ccy(),
                    invoice.createdDate(), invoice.reference());
        }
    }
}
