package dev.tkachuk.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ledger.
 *
 * @param topic           the topic the gateway publishes payment and refund events to
 * @param deadLetterTopic where an event goes that the ledger can't read, with the reason in its headers
 * @param replicas        the dead-letter topic's replication factor, made at startup
 */
@ConfigurationProperties("ledger")
public record LedgerProperties(String topic, String deadLetterTopic, short replicas) {

    public LedgerProperties {
        requireTopicName(topic, "topic");
        requireTopicName(deadLetterTopic, "dead-letter-topic");
        if (topic.equals(deadLetterTopic)) {
            throw new IllegalArgumentException("ledger.dead-letter-topic must not be the topic it reads, " + topic);
        }
        if (replicas < 1) {
            throw new IllegalArgumentException("ledger.replicas must be at least 1, got " + replicas);
        }
    }

    // Kafka's own rule for topic names.
    private static void requireTopicName(String name, String property) {
        if (name == null || !name.matches("[a-zA-Z0-9._-]{1,249}")) {
            throw new IllegalArgumentException("ledger." + property
                    + " must be 1 to 249 letters, digits, '.', '_' or '-', got " + name);
        }
    }
}
