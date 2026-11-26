package dev.tkachuk.gateway.event;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gateway.events: the payment-events topic on Kafka (spring.kafka says where Kafka is).
 *
 * @param enabled          whether the outbox's events are published; they are written to it either way, so
 *                         publishing later sends them all
 * @param topic            the topic's name
 * @param partitions       its partitions, made when the topic is; a payment's events always go to one of them
 * @param replicas         its replication factor: 1 for a single broker, 3 in production
 * @param publishInterval  how often the outbox is looked at
 * @param batchSize        events sent per run
 * @param sendTimeout      how long a run waits for Kafka to take its events
 * @param keepPublishedFor how long a published event stays in the outbox
 */
@ConfigurationProperties("gateway.events")
public record EventsProperties(boolean enabled, String topic, int partitions, short replicas,
        Duration publishInterval, int batchSize, Duration sendTimeout, Duration keepPublishedFor) {

    private static final String PREFIX = "gateway.events.";

    public EventsProperties {
        // Kafka's own rule for topic names.
        if (topic == null || !topic.matches("[a-zA-Z0-9._-]{1,249}")) {
            throw new IllegalArgumentException(PREFIX + "topic must be 1 to 249 letters, digits, '.', '_' or '-', got "
                    + topic);
        }
        requireAtLeastOne(partitions, "partitions");
        requireAtLeastOne(replicas, "replicas");
        requirePositive(publishInterval, "publish-interval");
        requireAtLeastOne(batchSize, "batch-size");
        requirePositive(sendTimeout, "send-timeout");
        requirePositive(keepPublishedFor, "keep-published-for");
    }

    private static void requireAtLeastOne(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(PREFIX + name + " must be at least 1, got " + value);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(PREFIX + name + " must be positive, got " + value);
        }
    }
}
