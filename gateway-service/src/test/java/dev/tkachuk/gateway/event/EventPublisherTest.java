package dev.tkachuk.gateway.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tkachuk.gateway.support.MovableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** The publisher's rules against stand-ins for the outbox and Kafka: order, the lock, failures, the topic. */
class EventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);

    private final EventOutboxRepository outbox = mock(EventOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = kafkaTemplate();
    private final KafkaAdmin admin = mock(KafkaAdmin.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock(NOW);
    private EventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = publisher(Duration.ofSeconds(15));
        when(outbox.lockForPublishing()).thenReturn(true);
        when(outbox.oldestUnpublishedAt()).thenReturn(Optional.empty());
    }

    @Test
    void shouldSendDueEventsInOrderAndMarkThemPublished() {
        when(outbox.findUnpublished(100)).thenReturn(List.of(event(1), event(2), event(3)));
        when(kafka.send(anyRecord())).thenReturn(taken());

        assertThat(publisher.publish()).isEqualTo(3);

        ArgumentCaptor<ProducerRecord<String, String>> sent = recordCaptor();
        verify(kafka, times(3)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::value)
                .containsExactly(payload(1), payload(2), payload(3));
        verify(outbox).markPublished(List.of(1L, 2L, 3L), NOW);
        assertThat(meters.counter(EventPublisher.PUBLISHED).count()).isEqualTo(3);
    }

    @Test
    void shouldMarkOnlyEventsBeforeFirstOneKafkaDidNotTake() {
        when(outbox.findUnpublished(100)).thenReturn(List.of(event(1), event(2), event(3)));
        when(kafka.send(anyRecord())).thenReturn(taken()).thenReturn(refused()).thenReturn(taken());

        assertThat(publisher.publish()).isEqualTo(1);

        verify(outbox).markPublished(List.of(1L), NOW);
        assertThat(meters.counter(EventPublisher.FAILURES).count()).isEqualTo(1);
    }

    // The producer refuses at once when it can't learn where the topic is; each further send would wait as long.
    @Test
    void shouldStopSendingWhenKafkaRefusesAtOnce() {
        when(outbox.findUnpublished(100)).thenReturn(List.of(event(1), event(2), event(3)));
        when(kafka.send(anyRecord())).thenReturn(refused());

        assertThat(publisher.publish()).isZero();

        verify(kafka, times(1)).send(anyRecord());
        verify(outbox, never()).markPublished(anyList(), any());
    }

    @Test
    void shouldStopWaitingForKafkaAfterSendTimeout() {
        publisher = publisher(Duration.ofMillis(100));
        when(outbox.findUnpublished(100)).thenReturn(List.of(event(1), event(2)));
        when(kafka.send(anyRecord())).thenReturn(taken()).thenReturn(new CompletableFuture<>());

        assertThat(publisher.publish()).isEqualTo(1);

        verify(outbox).markPublished(List.of(1L), NOW);
        assertThat(meters.counter(EventPublisher.FAILURES).count()).isEqualTo(1);
    }

    @Test
    void shouldLeaveEventsToInstanceThatHoldsLock() {
        when(outbox.lockForPublishing()).thenReturn(false);

        assertThat(publisher.publish()).isZero();

        verify(outbox, never()).findUnpublished(anyInt());
        verifyNoInteractions(kafka, admin);
    }

    @Test
    void shouldMakeTopicOnFirstRunAndAgainAfterFailure() {
        when(outbox.findUnpublished(100)).thenReturn(List.of(event(1)));
        when(kafka.send(anyRecord())).thenReturn(taken()).thenReturn(taken()).thenReturn(refused())
                .thenReturn(taken());

        publisher.publish();
        publisher.publish();
        publisher.publish();
        clock.move(Duration.ofSeconds(1));
        publisher.publish();

        ArgumentCaptor<NewTopic> topics = ArgumentCaptor.forClass(NewTopic.class);
        verify(admin, times(2)).createOrModifyTopics(topics.capture());
        NewTopic topic = topics.getValue();
        assertThat(topic.name()).isEqualTo("payment-events");
        assertThat(topic.numPartitions()).isEqualTo(3);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    // Consumers subscribe before the first payment, so the topic is there before any event.
    @Test
    void shouldMakeTopicOnFirstRunEvenWithoutEvents() {
        when(outbox.findUnpublished(100)).thenReturn(List.of());

        publisher.publish();
        publisher.publish();

        verify(admin, times(1)).createOrModifyTopics(any(NewTopic.class));
        verifyNoInteractions(kafka);
    }

    @Test
    void shouldTryAgainLaterWhenTopicCannotBeMade() {
        when(outbox.findUnpublished(100)).thenReturn(List.of(event(1)));
        doThrow(new KafkaException("Timed out waiting to get existing topics")).doNothing()
                .when(admin).createOrModifyTopics(any(NewTopic.class));
        when(kafka.send(anyRecord())).thenReturn(taken());

        assertThat(publisher.publish()).isZero();
        verifyNoInteractions(kafka);
        assertThat(meters.counter(EventPublisher.FAILURES).count()).isEqualTo(1);

        clock.move(Duration.ofSeconds(1));
        assertThat(publisher.publish()).isEqualTo(1);
        verify(admin, times(2)).createOrModifyTopics(any(NewTopic.class));
    }

    @Test
    void shouldNotTryAgainBeforeRetryDelayIsOver() {
        when(outbox.findUnpublished(100)).thenReturn(List.of(event(1)));
        when(kafka.send(anyRecord())).thenReturn(refused());

        publisher.publish();
        clock.move(Duration.ofMillis(500));
        publisher.publish();
        verify(outbox, times(1)).lockForPublishing();

        clock.move(Duration.ofMillis(500));
        publisher.publish();
        verify(outbox, times(2)).lockForPublishing();
    }

    @Test
    void shouldWaitLongerAfterEachFailureUpToHalfMinute() {
        assertThat(IntStream.rangeClosed(1, 8).mapToObj(EventPublisher::retryDelay)).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    @Test
    void shouldDeletePublishedEventsOnceAnHour() {
        when(outbox.findUnpublished(100)).thenReturn(List.of());

        publisher.publish();
        publisher.publish();
        verify(outbox, times(1)).deletePublishedBefore(any());
        verify(outbox).deletePublishedBefore(NOW.minus(WEEK));

        clock.move(Duration.ofHours(1));
        publisher.publish();
        verify(outbox).deletePublishedBefore(NOW.plus(Duration.ofHours(1)).minus(WEEK));
    }

    @Test
    void shouldReportHowLongOldestEventHasWaited() {
        when(outbox.findUnpublished(100)).thenReturn(List.of(event(1)));
        when(kafka.send(anyRecord())).thenReturn(refused());
        when(outbox.oldestUnpublishedAt()).thenReturn(Optional.of(NOW.minusSeconds(42)));

        publisher.publish();

        assertThat(meters.get(EventPublisher.LAG).gauge().value()).isEqualTo(42.0);
    }

    @Test
    void shouldScheduleRunsOnlyWhenEnabled() {
        ScheduledTaskRegistrar enabled = new ScheduledTaskRegistrar();
        publisher.configureTasks(enabled);
        ScheduledTaskRegistrar disabled = new ScheduledTaskRegistrar();
        publisher(false, Duration.ofSeconds(15)).configureTasks(disabled);

        assertThat(enabled.getFixedDelayTaskList()).hasSize(1);
        assertThat(disabled.getFixedDelayTaskList()).isEmpty();
    }

    private EventPublisher publisher(Duration sendTimeout) {
        return publisher(true, sendTimeout);
    }

    private EventPublisher publisher(boolean enabled, Duration sendTimeout) {
        EventsProperties properties = new EventsProperties(enabled, "payment-events", 3, (short) 1,
                Duration.ofMillis(200), 100, sendTimeout, WEEK);
        return new EventPublisher(outbox, kafka, admin, properties, new NoTransactions(), clock, meters);
    }

    private static OutboxEvent event(long seq) {
        return new OutboxEvent(seq, UUID.randomUUID(), UUID.randomUUID(), "payment.succeeded", payload(seq), null,
                NOW.minusSeconds(seq));
    }

    private static String payload(long seq) {
        return "{\"seq\":" + seq + "}";
    }

    @SuppressWarnings("unchecked")
    private static ProducerRecord<String, String> anyRecord() {
        return any(ProducerRecord.class);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> taken() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    private static CompletableFuture<SendResult<String, String>> refused() {
        return CompletableFuture.failedFuture(new KafkaException("Kafka is down"));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<ProducerRecord<String, String>> recordCaptor() {
        return ArgumentCaptor.forClass(ProducerRecord.class);
    }

    /** Transactions that do nothing: the outbox is a stand-in too. */
    private static final class NoTransactions extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
