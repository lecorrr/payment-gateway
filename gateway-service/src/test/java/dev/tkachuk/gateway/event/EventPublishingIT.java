package dev.tkachuk.gateway.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.gateway.support.EventContract;
import dev.tkachuk.gateway.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox's events on the payment-events topic of a real Kafka: keyed by the payment, with CloudEvents headers,
 * their data as docs/payment-events.yaml describes it, a refund's after its payment's, and the same id as the
 * change's webhook.
 */
class EventPublishingIT extends IntegrationTest {

    private static final String TOPIC = "payment-events";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private ConsumerFactory<String, String> consumers;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void shouldPublishSucceededPaymentAsCloudEvent() throws Exception {
        String paymentId = succeededPayment("event-1");

        List<ConsumerRecord<String, String>> records = recordsOf(paymentId, 1);

        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        assertThat(record.key()).isEqualTo(paymentId);
        assertThat(header(record, "ce_specversion")).isEqualTo("1.0");
        assertThat(header(record, "ce_type")).isEqualTo("payment.succeeded");
        assertThat(header(record, "ce_source")).isEqualTo("/payment-gateway");
        assertThat(header(record, "ce_subject")).isEqualTo(paymentId);
        assertThat(header(record, "content-type")).isEqualTo("application/json");
        assertThat(header(record, "ce_id")).isEqualTo(outboxIdOf(paymentId, "payment.succeeded"));
        assertThat(Instant.parse(header(record, "ce_time"))).isBefore(Instant.now().plusSeconds(1));

        String data = record.value();
        assertThat(JsonPath.<String>read(data, "$.paymentId")).isEqualTo(paymentId);
        assertThat(JsonPath.<String>read(data, "$.merchantId")).isEqualTo(demoMerchantId());
        assertThat(JsonPath.<String>read(data, "$.orderId")).isEqualTo("ORDER-1001");
        assertThat(JsonPath.<String>read(data, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(data, "$.amount")).isEqualTo(15000);
        assertThat(JsonPath.<String>read(data, "$.currency")).isEqualTo("UAH");
        assertThat(JsonPath.<String>read(data, "$.provider")).isEqualTo("acquirer-sim");
        assertThat(JsonPath.<String>read(data, "$.providerReference")).isEqualTo("ch_test_1");
        assertThat(Instant.parse(JsonPath.<String>read(data, "$.occurredAt")))
                .isEqualTo(Instant.parse(header(record, "ce_time")));
    }

    @Test
    void shouldPublishRefundAfterItsPaymentUnderPaymentsKey() throws Exception {
        String paymentId = succeededPayment("event-2");
        acquirerAnswersRefund(refund(201, "succeeded", null));
        createRefund(paymentId, "refund-1", 5000);

        List<ConsumerRecord<String, String>> records = recordsOf(paymentId, 2);

        assertThat(records).extracting(record -> header(record, "ce_type"))
                .containsExactly("payment.succeeded", "refund.succeeded");
        ConsumerRecord<String, String> refund = records.get(1);
        assertThat(refund.partition()).isEqualTo(records.get(0).partition());
        assertThat(header(refund, "ce_subject")).isEqualTo(paymentId);
        assertThat(JsonPath.<String>read(refund.value(), "$.paymentId")).isEqualTo(paymentId);
        assertThat(JsonPath.<String>read(refund.value(), "$.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(refund.value(), "$.amount")).isEqualTo(5000);
        assertThat(JsonPath.<String>read(refund.value(), "$.provider")).isEqualTo("acquirer-sim");
    }

    @Test
    void shouldWriteDataAndHeadersAsContractDescribes() throws Exception {
        String paymentId = succeededPayment("event-3");
        acquirerAnswersRefund(refund(201, "succeeded", null));
        createRefund(paymentId, "refund-1", 5000);
        EventContract contract = EventContract.read();

        List<ConsumerRecord<String, String>> records = recordsOf(paymentId, 2);

        assertMatches(records.get(0), contract, "paymentEventData", "paymentEventHeaders");
        assertMatches(records.get(1), contract, "refundEventData", "refundEventHeaders");
    }

    @Test
    void shouldGiveEventSameIdAsItsWebhook() throws Exception {
        jdbc.update("""
                INSERT INTO webhook_endpoints (merchant_id, url, secret, created_at, updated_at)
                SELECT id, ?, ?, now(), now() FROM merchants WHERE code = 'demo-shop'""",
                merchantEndpoint.baseUrl() + "/webhooks", "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        String paymentId = succeededPayment("event-4");

        UUID webhookId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE payment_id = ?::uuid", UUID.class,
                paymentId);

        assertThat(webhookId).hasToString(outboxIdOf(paymentId, "payment.succeeded"));
    }

    @Test
    void shouldMarkEventPublishedOnceKafkaTookIt() throws Exception {
        String paymentId = succeededPayment("event-5");

        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE payment_id = ?::uuid AND published_at IS NOT NULL",
                Integer.class, paymentId)).isEqualTo(1));
    }

    private void assertMatches(ConsumerRecord<String, String> record, EventContract contract, String dataSchema,
            String headersSchema) {
        JsonNode data = jsonMapper.readTree(record.value());
        List<String> fields = new ArrayList<>(data.propertyNames());
        assertThat(fields).as(dataSchema).containsExactlyInAnyOrderElementsOf(contract.properties(dataSchema));
        assertThat(contract.required(dataSchema)).allSatisfy(field ->
                assertThat(data.get(field).isNull()).as(field + " is required").isFalse());

        List<String> headers = new ArrayList<>();
        record.headers().forEach(header -> headers.add(header.key()));
        assertThat(contract.properties(headersSchema)).as(headersSchema).containsAll(headers);
        assertThat(headers).as(headersSchema).containsAll(contract.required(headersSchema));
    }

    // Waits until the payment's events are published, then reads the topic from the start for the ones under its
    // key: the topic has every test's events.
    private List<ConsumerRecord<String, String>> recordsOf(String paymentId, int expected) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE payment_id = ?::uuid AND published_at IS NOT NULL",
                Integer.class, paymentId)).isEqualTo(expected));
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("event-publishing-it-" + UUID.randomUUID(),
                null)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(partition -> new TopicPartition(TOPIC, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            Instant deadline = Instant.now().plus(WAIT);
            while (!caughtUp(consumer, ends) && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (paymentId.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    private static boolean caughtUp(Consumer<String, String> consumer, Map<TopicPartition, Long> ends) {
        return ends.entrySet().stream().allMatch(end -> consumer.position(end.getKey()) >= end.getValue());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("header " + name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private String outboxIdOf(String paymentId, String type) {
        return jdbc.queryForObject("SELECT id FROM event_outbox WHERE payment_id = ?::uuid AND type = ?",
                UUID.class, paymentId, type).toString();
    }

    private String demoMerchantId() {
        return jdbc.queryForObject("SELECT id FROM merchants WHERE code = 'demo-shop'", UUID.class).toString();
    }
}
