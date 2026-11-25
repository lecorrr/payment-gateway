package dev.tkachuk.gateway.webhook;

import java.net.URI;
import java.util.UUID;

/** Where a merchant's webhooks go, and the secret they are signed with; the secret masks itself in toString(). */
public record WebhookEndpoint(UUID merchantId, URI url, WebhookSecret secret) {
}
