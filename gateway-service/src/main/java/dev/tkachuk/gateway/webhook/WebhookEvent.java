package dev.tkachuk.gateway.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * An event of the outbox, as the dispatcher needs it. The id is the webhook-id header, the same for every attempt;
 * payload is the exact JSON body; attempts counts the attempts made before this one.
 */
record WebhookEvent(UUID id, UUID merchantId, UUID paymentId, String type, String payload, int attempts,
        Instant createdAt) {
}
