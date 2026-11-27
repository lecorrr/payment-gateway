package dev.tkachuk.loadtest;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The payments the simulation sends, without end: each a new order, Idempotency-Key and card, for 150.00 UAH, the
 * smoke test's amount. Gatling takes one for every virtual user, from more than one thread.
 */
final class NewPayments implements Iterator<Map<String, Object>> {

    static final String IDEMPOTENCY_KEY = "idempotencyKey";
    static final String BODY = "body";

    private final String runId;
    private final TestCards cards;
    private final AtomicLong number = new AtomicLong();

    NewPayments(String runId, TestCards cards) {
        this.runId = runId;
        this.cards = cards;
    }

    @Override
    public boolean hasNext() {
        return true;
    }

    @Override
    public Map<String, Object> next() {
        long payment = number.incrementAndGet();
        String body = String.format(Locale.ROOT, """
                {"orderId": "LOAD-%s-%d", "amount": 15000, "currency": "UAH", \
                "card": {"number": "%s", "expiryMonth": 12, "expiryYear": 2030, "cvc": "123"}}""",
                runId, payment, cards.nextNumber());
        return Map.of(IDEMPOTENCY_KEY, "load-" + runId + "-" + payment, BODY, body);
    }
}
