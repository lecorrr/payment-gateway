package dev.tkachuk.gateway.webhook;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events for merchants' webhooks, written to the outbox inside the transaction that makes the change they report,
 * so an event exists exactly when its change does. WebhookDispatcher sends them once that transaction has
 * committed; the HTTP call never holds the transaction open.
 */
@Service
public class WebhookOutbox {

    private final WebhookEventRepository events;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    WebhookOutbox(WebhookEventRepository events, JsonMapper jsonMapper, Clock clock) {
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * Stores the event with its body as the JSON it will be sent as. A merchant without a webhook endpoint gets
     * nothing stored. Must be called in a transaction: without one, a failure between the change and this call
     * would lose the event.
     */
    public void add(UUID merchantId, UUID paymentId, String type, Object body) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A webhook event must be added in the transaction of its change");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        events.insertIfEndpoint(new WebhookEvent(UUID.randomUUID(), merchantId, paymentId, type,
                jsonMapper.writeValueAsString(body), 0, now));
    }
}
