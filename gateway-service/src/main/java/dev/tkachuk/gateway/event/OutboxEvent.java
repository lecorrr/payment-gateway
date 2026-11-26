package dev.tkachuk.gateway.event;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of event_outbox. seq orders the events as they were written (0 before the row is stored); id is the
 * CloudEvents id; payload is the event's data as JSON; traceparent is the W3C trace context of the change, when it
 * was traced; createdAt is when the change was made, the event's time.
 */
record OutboxEvent(long seq, UUID id, UUID paymentId, String type, String payload, @Nullable String traceparent,
        Instant createdAt) {
}
