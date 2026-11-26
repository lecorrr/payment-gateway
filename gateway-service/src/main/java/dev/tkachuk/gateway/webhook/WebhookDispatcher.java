package dev.tkachuk.gateway.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Sends the outbox's events every dispatch-interval, one run at a time per instance. Events are claimed with a
 * lease, as the checker claims payments, so instances don't send the same event at once. An instance that stops
 * mid-send leaves its events to be claimed again when the lease runs out: delivery is at least once, and merchants
 * deduplicate by webhook-id. gateway.webhooks counts the attempts by what came of them: delivered, retrying (it
 * failed, and another attempt follows) or given_up.
 */
@Component
class WebhookDispatcher implements SchedulingConfigurer {

    static final String DELIVERIES = "gateway.webhooks";

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final WebhookEventRepository events;
    private final WebhookEndpoints endpoints;
    private final WebhookSender sender;
    private final WebhookProperties properties;
    private final RetrySchedule retries;
    private final Clock clock;
    private final Counter delivered;
    private final Counter retrying;
    private final Counter givenUp;

    WebhookDispatcher(WebhookEventRepository events, WebhookEndpoints endpoints, WebhookSender sender,
            WebhookProperties properties, Clock clock, MeterRegistry meters) {
        this.events = events;
        this.endpoints = endpoints;
        this.sender = sender;
        this.properties = properties;
        this.retries = new RetrySchedule(properties.retryDelays());
        this.clock = clock;
        this.delivered = deliveries(meters, "delivered");
        this.retrying = deliveries(meters, "retrying");
        this.givenUp = deliveries(meters, "given_up");
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::dispatch, properties.dispatchInterval());
    }

    /** Tries the events that are due; returns how many. */
    int dispatch() {
        Instant now = now();
        List<WebhookEvent> due = events.claimDue(now, now.plus(properties.lease()), properties.batchSize());
        for (WebhookEvent event : due) {
            Optional<WebhookEndpoint> endpoint = endpoints.find(event.merchantId());
            if (endpoint.isEmpty()) {
                events.markFailed(event.id(), "The merchant has no webhook endpoint any more", false);
                givenUp.increment();
                log.warn("Webhook {} for payment {} given up: the merchant has no webhook endpoint any more",
                        event.id(), event.paymentId());
                continue;
            }
            record(event, send(endpoint.get(), event));
        }
        return due.size();
    }

    private DeliveryResult send(WebhookEndpoint endpoint, WebhookEvent event) {
        try {
            return sender.send(endpoint, event, now());
        } catch (RuntimeException e) {
            // A bug or bad data rather than the endpoint's answer. Logged with its stack trace and counted as a
            // failed attempt, so one such event can't stop the others or be tried forever.
            log.error("Webhook {} for payment {} could not be sent", event.id(), event.paymentId(), e);
            return DeliveryResult.failed(e.getClass().getSimpleName());
        }
    }

    private void record(WebhookEvent event, DeliveryResult result) {
        int attemptsMade = event.attempts() + 1;
        switch (result.outcome()) {
            case DELIVERED -> {
                events.markDelivered(event.id(), now());
                delivered.increment();
                log.info("Webhook {} {} for payment {} delivered, attempt {}", event.id(), event.type(),
                        event.paymentId(), attemptsMade);
            }
            case GONE -> {
                events.markFailed(event.id(), errorOf(result), true);
                givenUp.increment();
                log.warn("Webhook {} for payment {} given up: the endpoint answered 410 Gone", event.id(),
                        event.paymentId());
            }
            case FAILED -> retryOrGiveUp(event, attemptsMade, errorOf(result));
        }
    }

    private void retryOrGiveUp(WebhookEvent event, int attemptsMade, String error) {
        Optional<Instant> next = retries.nextAttemptAt(attemptsMade, now(), ThreadLocalRandom.current().nextDouble());
        if (next.isPresent()) {
            events.scheduleRetry(event.id(), error, next.get());
            retrying.increment();
            log.warn("Webhook {} for payment {} not delivered, attempt {} of {}: {}; next attempt at {}", event.id(),
                    event.paymentId(), attemptsMade, retries.maxAttempts(), error, next.get());
        } else {
            events.markFailed(event.id(), error, true);
            givenUp.increment();
            log.warn("Webhook {} for payment {} given up after {} attempts: {}", event.id(), event.paymentId(),
                    attemptsMade, error);
        }
    }

    private static Counter deliveries(MeterRegistry meters, String outcome) {
        return Counter.builder(DELIVERIES)
                .description("Attempts to deliver merchants' webhooks, by what came of them")
                .tag("outcome", outcome)
                .register(meters);
    }

    private static String errorOf(DeliveryResult result) {
        String error = result.error() == null ? "not delivered" : result.error();
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
