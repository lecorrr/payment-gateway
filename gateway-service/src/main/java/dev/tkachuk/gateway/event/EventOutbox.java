package dev.tkachuk.gateway.event;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events for the payment-events topic, written to the outbox inside the transaction that makes the change they
 * report, as webhook events are, so an event exists exactly when its change does. EventPublisher sends them to
 * Kafka afterwards, in the order they were written. With each event goes the trace context of the request that made
 * the change, when there is one, so what consumers do with the event shows in that request's trace.
 */
@Service
public class EventOutbox {

    /** The W3C trace context header, which is at most 55 characters in its version 00. */
    static final String TRACEPARENT = "traceparent";
    private static final int TRACEPARENT_LENGTH = 55;

    private final EventOutboxRepository events;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<Tracer> tracers;
    private final ObjectProvider<Propagator> propagators;

    EventOutbox(EventOutboxRepository events, JsonMapper jsonMapper, ObjectProvider<Tracer> tracers,
            ObjectProvider<Propagator> propagators) {
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.tracers = tracers;
        this.propagators = propagators;
    }

    /**
     * Stores the event, its data as the JSON that goes onto the topic. id is the event's CloudEvents id; the same
     * change's webhook event has the same id. Must be called in a transaction: without one, a failure between the
     * change and this call would lose the event.
     */
    public void add(UUID id, UUID paymentId, String type, Instant occurredAt, Object data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An event must be added in the transaction of its change");
        }
        events.insert(new OutboxEvent(0, id, paymentId, type, jsonMapper.writeValueAsString(data),
                currentTraceparent(), occurredAt.truncatedTo(ChronoUnit.MICROS)));
    }

    // The current span's context as the propagation Spring Boot set up writes it; without tracing, nothing.
    private @Nullable String currentTraceparent() {
        Tracer tracer = tracers.getIfAvailable();
        Propagator propagator = propagators.getIfAvailable();
        if (tracer == null || propagator == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        propagator.inject(span.context(), headers, (carrier, key, value) -> {
            if (carrier != null) {
                carrier.put(key, value);
            }
        });
        String traceparent = headers.get(TRACEPARENT);
        return traceparent != null && traceparent.length() <= TRACEPARENT_LENGTH ? traceparent : null;
    }
}
