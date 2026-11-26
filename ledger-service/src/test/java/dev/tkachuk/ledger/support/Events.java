package dev.tkachuk.ledger.support;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.jspecify.annotations.Nullable;

/**
 * Records of the payment-events topic as the gateway sends them (docs/payment-events.yaml): a CloudEvent in binary
 * mode, keyed by the payment's id, with its data as JSON. The ledger shares no code with the gateway, so the tests
 * make them here.
 */
public final class Events {

    public static final String OCCURRED_AT = "2026-11-26T09:15:02.481233Z";

    private Events() {
    }

    public static ProducerRecord<String, String> record(UUID eventId, String type, UUID paymentId, String data) {
        return record(paymentId.toString(), data, Map.of(
                "ce_specversion", "1.0",
                "ce_id", eventId.toString(),
                "ce_source", "/payment-gateway",
                "ce_type", type,
                "ce_time", OCCURRED_AT,
                "ce_subject", paymentId.toString(),
                "content-type", "application/json"));
    }

    public static ProducerRecord<String, String> record(String key, String data, Map<String, String> headers) {
        Headers recordHeaders = new RecordHeaders();
        headers.forEach((name, value) -> recordHeaders.add(name, value.getBytes(StandardCharsets.UTF_8)));
        return new ProducerRecord<>(LedgerContainers.TOPIC, null, null, key, data, recordHeaders);
    }

    /** The record's last header of that name, as text, or null. */
    public static @Nullable String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** A payment's data with every field of the contract; status is SUCCEEDED, DECLINED and so on. */
    public static String paymentData(UUID paymentId, UUID merchantId, String status, long amount, String currency,
            String provider) {
        return """
                {"paymentId": "%s", "merchantId": "%s", "orderId": "ORDER-1001", "status": "%s", "amount": %d,
                 "currency": "%s", "provider": "%s", "providerReference": "ch_test_1", "declineCode": null,
                 "refundedAmount": 0, "occurredAt": "%s"}"""
                .formatted(paymentId, merchantId, status, amount, currency, provider, OCCURRED_AT);
    }

    /** A refund's data with every field of the contract. */
    public static String refundData(UUID refundId, UUID paymentId, UUID merchantId, String status, long amount,
            String currency, String provider) {
        return """
                {"refundId": "%s", "paymentId": "%s", "merchantId": "%s", "status": "%s", "amount": %d,
                 "currency": "%s", "provider": "%s", "providerReference": "re_test_1", "failureCode": null,
                 "occurredAt": "%s"}"""
                .formatted(refundId, paymentId, merchantId, status, amount, currency, provider, OCCURRED_AT);
    }
}
