package dev.tkachuk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.ledger.LedgerEvent.NotBooked;
import dev.tkachuk.ledger.LedgerEvent.PaymentSucceeded;
import dev.tkachuk.ledger.LedgerEvent.RefundSucceeded;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Records as docs/payment-events.yaml describes them are read; what doesn't fit is refused, and says why. */
class EventReaderTest {

    private static final UUID EVENT = UUID.fromString("6f1c2b7e-3a5d-4b8e-9c0f-1a2b3c4d5e6f");
    private static final UUID PAYMENT = UUID.fromString("0d7f5e2a-8c41-4c1b-9a6e-5b3f2d1c0e9a");
    private static final UUID MERCHANT = UUID.fromString("3b9d6c4e-1f2a-4e5b-8c7d-9e0f1a2b3c4d");
    private static final UUID REFUND = UUID.fromString("4c5d6e7f-8091-4a2b-b3c4-d5e6f7081920");
    private static final String OCCURRED_AT = "2026-11-26T09:15:02.481233Z";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EventReader reader = new EventReader(JSON);

    @Test
    void shouldReadSucceededPayment() {
        LedgerEvent event = reader.read(record("payment.succeeded", PAYMENT.toString(), json(paymentData())));

        assertThat(event).isEqualTo(new PaymentSucceeded(EVENT, PAYMENT, MERCHANT, "monobank", "UAH", 15000,
                Instant.parse(OCCURRED_AT)));
    }

    @Test
    void shouldReadSucceededRefund() {
        LedgerEvent event = reader.read(record("refund.succeeded", PAYMENT.toString(), json(refundData())));

        assertThat(event).isEqualTo(new RefundSucceeded(EVENT, REFUND, PAYMENT, MERCHANT, "monobank", "UAH", 5000,
                Instant.parse(OCCURRED_AT)));
    }

    // A field the contract adds later doesn't stop the ledger.
    @Test
    void shouldReadDataWithFieldsItDoesNotUse() {
        Map<String, Object> data = paymentData();
        data.put("riskScore", 12);

        assertThat(reader.read(record("payment.succeeded", PAYMENT.toString(), json(data))))
                .isInstanceOf(PaymentSucceeded.class);
    }

    // Their data isn't read, so it may be anything: only the two types that change the ledger have to be exact.
    @ParameterizedTest
    @ValueSource(strings = {"payment.requires_action", "payment.declined", "payment.failed", "refund.failed",
            "payment.disputed"})
    void shouldTakeOtherTypesWithoutReadingTheirData(String type) {
        LedgerEvent event = reader.read(record(type, PAYMENT.toString(), "{\"anything\": true}"));

        assertThat(event).isEqualTo(new NotBooked(EVENT, type, PAYMENT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void shouldRefuseRecordsThatAreNoCloudEvents(String what, ConsumerRecord<String, String> record, String why) {
        assertThatThrownBy(() -> reader.read(record))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining(why);
    }

    static Stream<Arguments> shouldRefuseRecordsThatAreNoCloudEvents() {
        String data = json(paymentData());
        return Stream.of(
                Arguments.of("no ce_specversion", record(without("ce_specversion"), PAYMENT.toString(), data),
                        "has no ce_specversion header"),
                Arguments.of("another version", record(with("ce_specversion", "0.3"), PAYMENT.toString(), data),
                        "ce_specversion is 0.3, not 1.0"),
                Arguments.of("no ce_id", record(without("ce_id"), PAYMENT.toString(), data), "has no ce_id header"),
                Arguments.of("ce_id no UUID", record(with("ce_id", "event-1"), PAYMENT.toString(), data),
                        "ce_id is no UUID: event-1"),
                Arguments.of("no ce_type", record(without("ce_type"), PAYMENT.toString(), data),
                        "has no ce_type header"),
                Arguments.of("empty ce_type", record(with("ce_type", ""), PAYMENT.toString(), data),
                        "ce_type of 0 characters"),
                Arguments.of("ce_type too long", record(with("ce_type", "x".repeat(65)), PAYMENT.toString(), data),
                        "ce_type of 65 characters"),
                Arguments.of("no key", record(headers("payment.succeeded"), null, data), "the record's key is missing"),
                Arguments.of("key no UUID", record(headers("payment.succeeded"), "payment-1", data),
                        "the record's key is no UUID: payment-1"),
                Arguments.of("no data", record(headers("payment.declined"), PAYMENT.toString(), null), "has no data"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void shouldRefuseSucceededPaymentsItCannotBook(String what, Consumer<Map<String, Object>> change, String why) {
        Map<String, Object> data = paymentData();
        change.accept(data);

        assertThatThrownBy(() -> reader.read(record("payment.succeeded", PAYMENT.toString(), json(data))))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining(why);
    }

    static Stream<Arguments> shouldRefuseSucceededPaymentsItCannotBook() {
        return Stream.of(
                change("no paymentId", data -> data.remove("paymentId"), "has no paymentId"),
                change("keyed by another payment", data -> data.put("paymentId", UUID.randomUUID().toString()),
                        "is keyed " + PAYMENT),
                change("not SUCCEEDED", data -> data.put("status", "DECLINED"), "its status is DECLINED"),
                change("no merchantId", data -> data.remove("merchantId"), "has no merchantId"),
                change("merchantId no UUID", data -> data.put("merchantId", "shop-1"), "no PaymentData"),
                change("amount 0", data -> data.put("amount", 0), "amount that isn't positive: 0"),
                change("negative amount", data -> data.put("amount", -100), "amount that isn't positive: -100"),
                change("amount a fraction", data -> data.put("amount", 150.5), "no PaymentData"),
                change("no currency", data -> data.remove("currency"), "no ISO 4217 currency: null"),
                change("currency in lowercase", data -> data.put("currency", "uah"), "no ISO 4217 currency: uah"),
                change("no provider", data -> data.remove("provider"), "no provider name"),
                change("provider with a space", data -> data.put("provider", "mono bank"), "no provider name"),
                change("provider too long", data -> data.put("provider", "p".repeat(33)), "no provider name"),
                change("no occurredAt", data -> data.remove("occurredAt"), "has no occurredAt"),
                change("occurredAt no time", data -> data.put("occurredAt", "yesterday"), "no PaymentData"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not json", "[]", "42", "{\"paymentId\": \"0d7f5e2a-8c41-4c1b-9a6e-5b3f2d1c0e9a\"} {}"})
    void shouldRefuseSucceededPaymentWhoseDataIsNoObject(String data) {
        assertThatThrownBy(() -> reader.read(record("payment.succeeded", PAYMENT.toString(), data)))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("Event " + EVENT + " has data that is no PaymentData");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void shouldRefuseSucceededRefundsItCannotBook(String what, Consumer<Map<String, Object>> change, String why) {
        Map<String, Object> data = refundData();
        change.accept(data);

        assertThatThrownBy(() -> reader.read(record("refund.succeeded", PAYMENT.toString(), json(data))))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining(why);
    }

    static Stream<Arguments> shouldRefuseSucceededRefundsItCannotBook() {
        return Stream.of(
                change("no refundId", data -> data.remove("refundId"), "has no refundId"),
                change("keyed by another payment", data -> data.put("paymentId", UUID.randomUUID().toString()),
                        "is keyed " + PAYMENT),
                change("FAILED", data -> data.put("status", "FAILED"), "its status is FAILED"),
                change("amount 0", data -> data.put("amount", 0), "amount that isn't positive: 0"));
    }

    private static Arguments change(String what, Consumer<Map<String, Object>> change, String why) {
        return Arguments.of(what, change, why);
    }

    private static Map<String, Object> paymentData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", PAYMENT.toString());
        data.put("merchantId", MERCHANT.toString());
        data.put("orderId", "ORDER-1001");
        data.put("status", "SUCCEEDED");
        data.put("amount", 15000);
        data.put("currency", "UAH");
        data.put("provider", "monobank");
        data.put("providerReference", "2603261234567AbCdEf");
        data.put("declineCode", null);
        data.put("refundedAmount", 0);
        data.put("occurredAt", OCCURRED_AT);
        return data;
    }

    private static Map<String, Object> refundData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", REFUND.toString());
        data.put("paymentId", PAYMENT.toString());
        data.put("merchantId", MERCHANT.toString());
        data.put("status", "SUCCEEDED");
        data.put("amount", 5000);
        data.put("currency", "UAH");
        data.put("provider", "monobank");
        data.put("providerReference", "2603269876543ZyXwVu");
        data.put("failureCode", null);
        data.put("occurredAt", OCCURRED_AT);
        return data;
    }

    private static String json(Map<String, Object> data) {
        return JSON.writeValueAsString(data);
    }

    private static Map<String, String> headers(String type) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ce_specversion", "1.0");
        headers.put("ce_id", EVENT.toString());
        headers.put("ce_source", "/payment-gateway");
        headers.put("ce_type", type);
        headers.put("ce_time", OCCURRED_AT);
        headers.put("ce_subject", PAYMENT.toString());
        headers.put("content-type", "application/json");
        return headers;
    }

    private static Map<String, String> without(String header) {
        Map<String, String> headers = headers("payment.succeeded");
        headers.remove(header);
        return headers;
    }

    private static Map<String, String> with(String header, String value) {
        Map<String, String> headers = headers("payment.succeeded");
        headers.put(header, value);
        return headers;
    }

    private static ConsumerRecord<String, String> record(String type, String key, String value) {
        return record(headers(type), key, value);
    }

    private static ConsumerRecord<String, String> record(Map<String, String> headers, String key, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 1, 42, key, value);
        headers.forEach((name, text) -> record.headers().add(name, text.getBytes(StandardCharsets.UTF_8)));
        return record;
    }
}
