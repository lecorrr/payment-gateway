package dev.tkachuk.ledger.support;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The ledger against PostgreSQL and Kafka in Testcontainers, reading payment-events as it does in production. The
 * tests send their events with the ledger's own KafkaTemplate. Every test starts with an empty ledger; the topics
 * keep every test's records, so each test goes by its own ids. All integration tests share this context, and so
 * its containers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(LedgerContainers.class)
public abstract class LedgerIntegrationTest {

    protected static final String DEAD_LETTER_TOPIC = "payment-events-ledger-dlt";
    protected static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected KafkaTemplate<String, String> kafka;

    @Autowired
    protected ConsumerFactory<String, String> consumers;

    @BeforeEach
    void startClean() {
        jdbc.execute("TRUNCATE postings, journal_entries, processed_events");
    }

    /** Sends the record and waits until Kafka has it. */
    protected void send(ProducerRecord<String, String> record) throws Exception {
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    /** Waits until the ledger has taken the event, whether it booked it or not. */
    protected void awaitTaken(UUID eventId) {
        await().atMost(WAIT).until(() -> taken(eventId));
    }

    protected boolean taken(UUID eventId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId);
        return count != null && count > 0;
    }

    protected ResultActions getPayment(UUID paymentId) throws Exception {
        return mockMvc.perform(get("/api/v1/payments/{id}", paymentId));
    }

    protected ResultActions getBalances(UUID merchantId) throws Exception {
        return mockMvc.perform(get("/api/v1/merchants/{id}/balances", merchantId));
    }

    /** The dead-letter topic's records with the CloudEvents id, read from its start. */
    protected List<ConsumerRecord<String, String>> deadLettersOf(String eventId) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("ledger-it-" + UUID.randomUUID(), null)) {
            TopicPartition partition = new TopicPartition(DEAD_LETTER_TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            Instant deadline = Instant.now().plus(WAIT);
            while (consumer.position(partition) < end && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (eventId.equals(Events.header(record, "ce_id"))) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }
}
