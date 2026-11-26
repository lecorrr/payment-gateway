package dev.tkachuk.merchant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The webhooks that came, by webhook-id, in memory. A retry or a duplicate of one adds to its deliveries instead of
 * making a second entry, which is how a merchant deduplicates. Past max-events the oldest is forgotten.
 */
@Component
class ReceivedEvents {

    /**
     * refundId: set for a refund's event, whose status is the refund's; paymentId is always the payment's. changedAt:
     * when the payment or refund changed, from the event itself, which orders events of one payment whatever order
     * they came in; takenAt: when a delivery was first answered 2xx, which is when the shop acted on it. Until then
     * only webhookId and deliveries are known.
     */
    record Event(String webhookId, int deliveries, String type, String paymentId, String refundId, String status,
            Instant changedAt, Instant takenAt) {

        Event withOneMoreDelivery() {
            return new Event(webhookId, deliveries + 1, type, paymentId, refundId, status, changedAt, takenAt);
        }

        boolean isTaken() {
            return takenAt != null;
        }

        boolean isAboutPayment() {
            return refundId == null;
        }
    }

    private final Map<String, Event> byWebhookId;

    ReceivedEvents(MerchantSimProperties properties) {
        int maxEvents = properties.maxEvents();
        this.byWebhookId = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Event> eldest) {
                return size() > maxEvents;
            }
        };
    }

    /** Counts a delivery of this webhook; returns how many have come, this one included. */
    synchronized int countDelivery(String webhookId) {
        Event event = byWebhookId.get(webhookId);
        Event counted = event == null
                ? new Event(webhookId, 1, null, null, null, null, null, null)
                : event.withOneMoreDelivery();
        byWebhookId.put(webhookId, counted);
        return counted.deliveries();
    }

    /**
     * Records what the webhook says the first time one with this id is taken; returns false for a duplicate.
     * refundId: null for a payment's event.
     */
    synchronized boolean take(String webhookId, String type, String paymentId, String refundId, String status,
            Instant changedAt, Instant now) {
        Event event = byWebhookId.get(webhookId);
        if (event != null && event.isTaken()) {
            return false;
        }
        int deliveries = event == null ? 1 : event.deliveries();
        byWebhookId.put(webhookId, new Event(webhookId, deliveries, type, paymentId, refundId, status, changedAt,
                now));
        return true;
    }

    /** Newest first. */
    synchronized List<Event> all() {
        List<Event> events = new ArrayList<>(byWebhookId.values());
        Collections.reverse(events);
        return events;
    }

    /**
     * The payment's status as its most recent change reported it, whatever order the webhooks came in; its refunds'
     * events don't count.
     */
    synchronized Optional<String> statusOf(String paymentId) {
        return byWebhookId.values().stream()
                .filter(event -> event.isTaken() && event.isAboutPayment() && paymentId.equals(event.paymentId()))
                .max(Comparator.comparing(Event::changedAt))
                .map(Event::status);
    }
}
