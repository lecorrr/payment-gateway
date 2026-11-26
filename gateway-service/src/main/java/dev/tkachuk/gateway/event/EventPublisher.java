package dev.tkachuk.gateway.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.NewTopic;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the outbox's events to the payment-events topic every publish-interval. One instance publishes at a time,
 * under a lock held for the run's transaction, and sends the oldest events in their order; an event is marked
 * published once Kafka has taken it and every event before it. When a send fails, the events from it on stay in
 * the outbox and go in a later run, which waits longer after each failure, up to half a minute: publishing is at
 * least once, and consumers deduplicate by the CloudEvents id. The topic is made by the first run that reaches
 * Kafka, events or not, so consumers find it before the first event; the gateway neither waits for Kafka at startup
 * nor stops when Kafka is down: payments go on, and their events wait.
 */
@Component
class EventPublisher implements SchedulingConfigurer {

    static final String PUBLISHED = "gateway.events.published";
    static final String FAILURES = "gateway.events.publish.failures";
    static final String LAG = "gateway.events.lag";

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration CLEAN_UP_EVERY = Duration.ofHours(1);

    private final EventOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaAdmin admin;
    private final EventsProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final NewTopic topic;
    private final Counter published;
    private final Counter failures;
    private final AtomicLong lagMillis = new AtomicLong();

    // The scheduler runs publish() one run at a time, each after the last has ended, so these need no locking.
    private boolean topicReady;
    private int failuresInARow;
    private @Nullable Instant retryAt;
    private @Nullable Instant nextCleanUpAt;

    EventPublisher(EventOutboxRepository outbox, KafkaTemplate<String, String> kafka, KafkaAdmin admin,
            EventsProperties properties, PlatformTransactionManager transactionManager, Clock clock,
            MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.admin = admin;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.topic = TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicas())
                .build();
        this.published = Counter.builder(PUBLISHED)
                .description("Events sent to the payment-events topic and taken by Kafka")
                .register(meters);
        this.failures = Counter.builder(FAILURES)
                .description("Runs of the event publisher that Kafka didn't take all events of")
                .register(meters);
        Gauge.builder(LAG, lagMillis, millis -> millis.get() / 1000.0)
                .description("How long the oldest event not published yet has waited in the outbox")
                .baseUnit("seconds")
                .register(meters);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (properties.enabled()) {
            registrar.addFixedDelayTask(this::publish, properties.publishInterval());
        } else {
            log.warn("Events are not published: gateway.events.enabled is false. They wait in the outbox.");
        }
    }

    /** Sends the events that are due, unless another instance is doing so; returns how many Kafka took. */
    int publish() {
        Instant now = clock.instant();
        Instant retry = retryAt;
        if (retry != null && now.isBefore(retry)) {
            return 0;
        }
        Integer sent = transactions.execute(status -> outbox.lockForPublishing() ? publishLocked() : 0);
        return sent == null ? 0 : sent;
    }

    private int publishLocked() {
        cleanUpIfDue();
        List<OutboxEvent> due = outbox.findUnpublished(properties.batchSize());
        List<Long> taken = new ArrayList<>();
        try {
            makeTopicIfNeeded();
            if (!due.isEmpty()) {
                sendInOrder(due, taken);
            }
            recovered();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            failed(due.size() - taken.size(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (!taken.isEmpty()) {
                outbox.markPublished(taken, clock.instant());
                published.increment(taken.size());
            }
            Instant oldest = outbox.oldestUnpublishedAt().orElse(null);
            lagMillis.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toMillis()));
        }
        return taken.size();
    }

    // Sends the batch at once and waits for Kafka's answers in order, so taken ends with the last event that Kafka
    // took after all the ones before it. A send that fails at once, as when the producer couldn't learn where the
    // topic is within max.block.ms, ends the sending: each further one would wait as long again.
    private void sendInOrder(List<OutboxEvent> due, List<Long> taken)
            throws ExecutionException, TimeoutException, InterruptedException {
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(due.size());
        for (OutboxEvent event : due) {
            CompletableFuture<SendResult<String, String>> send =
                    kafka.send(CloudEventRecords.recordOf(properties.topic(), event));
            sends.add(send);
            if (send.isCompletedExceptionally()) {
                break;
            }
        }
        long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        for (int i = 0; i < sends.size(); i++) {
            sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            taken.add(due.get(i).seq());
        }
    }

    // Once Kafka answers, and again after a run that failed, in case the topic was what failed.
    private void makeTopicIfNeeded() {
        if (!topicReady) {
            admin.createOrModifyTopics(topic);
            topicReady = true;
        }
    }

    private void recovered() {
        if (failuresInARow > 0) {
            log.info("Events are published again, after {} failed runs", failuresInARow);
        }
        failuresInARow = 0;
        retryAt = null;
    }

    // Logged on the first failure and then on every tenth, so a Kafka that is down for long doesn't fill the log.
    private void failed(int notSent, Exception e) {
        failures.increment();
        topicReady = false;
        failuresInARow++;
        Duration delay = retryDelay(failuresInARow);
        retryAt = clock.instant().plus(delay);
        if (failuresInARow == 1 || failuresInARow % 10 == 0) {
            log.warn("Publishing to Kafka failed, {} runs in a row, with {} events of this run not sent: {}; "
                    + "trying again in {}", failuresInARow, notSent, messageOf(e), delay);
        }
    }

    private void cleanUpIfDue() {
        Instant now = clock.instant();
        Instant due = nextCleanUpAt;
        if (due != null && now.isBefore(due)) {
            return;
        }
        nextCleanUpAt = now.plus(CLEAN_UP_EVERY);
        int deleted = outbox.deletePublishedBefore(now.minus(properties.keepPublishedFor()));
        if (deleted > 0) {
            log.info("Deleted {} events published more than {} ago from the outbox", deleted,
                    properties.keepPublishedFor());
        }
    }

    /** 1 s after the first failure, doubling after each one that follows, at most 30 s. */
    static Duration retryDelay(int failuresInARow) {
        Duration delay = FIRST_RETRY_DELAY;
        for (int i = 1; i < failuresInARow && delay.compareTo(MAX_RETRY_DELAY) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(MAX_RETRY_DELAY) < 0 ? delay : MAX_RETRY_DELAY;
    }

    // An ExecutionException only wraps what Kafka answered.
    private static String messageOf(Exception e) {
        Throwable cause = e.getCause();
        Throwable reason = e instanceof ExecutionException && cause != null ? cause : e;
        return reason.getClass().getSimpleName() + ": " + reason.getMessage();
    }
}
