package dev.tkachuk.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens to an event the ledger couldn't take. One it can't read goes to the dead-letter topic at once, with
 * the reason in its headers, and the partition moves on. Any other failure, such as the database being down, is
 * tried again, after about 1 s and then about twice as long each time up to 30 s, until it works: skipping the event
 * would leave the ledger short of a payment. Its partition waits meanwhile, which the consumer lag shows.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {

    static final String DEAD_LETTERS = "ledger.dead.letters";

    private static final long FIRST_RETRY_DELAY_MS = 1_000;
    private static final long MAX_RETRY_DELAY_MS = 30_000;
    // Up to this much either way, growing with the delay, so ledgers that fail together, as when the database is
    // away, don't all come back at the same moment.
    private static final long RETRY_JITTER_MS = 300;

    /** Made at startup, with one partition: dead letters are few, and their order doesn't matter. */
    @Bean
    NewTopic deadLetterTopic(LedgerProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(1)
                .replicas(properties.replicas())
                .build();
    }

    /** Boot puts this on the listener's container. */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafka, LedgerProperties properties,
            MeterRegistry meters) {
        // Partition -1 lets Kafka choose: the record's own partition number may not exist on the dead-letter topic.
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(properties.deadLetterTopic(), -1));
        Counter sent = Counter.builder(DEAD_LETTERS)
                .description("Events the ledger couldn't read, sent to the dead-letter topic")
                .register(meters);
        // The recoverer throws when Kafka didn't take the dead letter; then the event is tried again and not counted.
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            deadLetters.accept(record, exception);
            sent.increment();
        };
        ExponentialBackOff backOff = new ExponentialBackOff(FIRST_RETRY_DELAY_MS, 2.0);
        backOff.setMaxInterval(MAX_RETRY_DELAY_MS);
        backOff.setJitter(RETRY_JITTER_MS);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}
