package dev.tkachuk.gateway.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

class CloudEventRecordsTest {

    private static final UUID EVENT = UUID.fromString("6f1c2b7e-3a5d-4b8e-9c0f-1a2b3c4d5e6f");
    private static final UUID PAYMENT = UUID.fromString("0d7f5e2a-8c41-4c1b-9a6e-5b3f2d1c0e9a");
    private static final Instant TIME = Instant.parse("2026-11-26T09:15:02.481233Z");
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void shouldPutEventIntoBinaryModeRecordKeyedByPayment() {
        ProducerRecord<String, String> record = CloudEventRecords.recordOf("payment-events",
                event("payment.succeeded", null));

        assertThat(record.topic()).isEqualTo("payment-events");
        assertThat(record.key()).isEqualTo(PAYMENT.toString());
        assertThat(record.value()).isEqualTo("{\"paymentId\":\"" + PAYMENT + "\"}");
        assertThat(record.partition()).isNull();
        assertThat(headersOf(record)).containsExactly(
                Map.entry("ce_specversion", "1.0"),
                Map.entry("ce_id", EVENT.toString()),
                Map.entry("ce_source", "/payment-gateway"),
                Map.entry("ce_type", "payment.succeeded"),
                Map.entry("ce_time", "2026-11-26T09:15:02.481233Z"),
                Map.entry("ce_subject", PAYMENT.toString()),
                Map.entry("content-type", "application/json"));
    }

    @Test
    void shouldCarryTraceContextOfChangeWhenItWasTraced() {
        ProducerRecord<String, String> record = CloudEventRecords.recordOf("payment-events",
                event("refund.succeeded", TRACEPARENT));

        assertThat(headersOf(record)).containsEntry("traceparent", TRACEPARENT);
    }

    // Kafka would otherwise show the time the change was made as the record's own, for a backlog sent late.
    @Test
    void shouldLeaveRecordTimeToKafka() {
        ProducerRecord<String, String> record = CloudEventRecords.recordOf("payment-events",
                event("payment.failed", null));

        assertThat(record.timestamp()).isNull();
    }

    private static OutboxEvent event(String type, String traceparent) {
        return new OutboxEvent(7, EVENT, PAYMENT, type, "{\"paymentId\":\"" + PAYMENT + "\"}", traceparent, TIME);
    }

    private static Map<String, String> headersOf(ProducerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        record.headers().forEach(header ->
                headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8)));
        return headers;
    }
}
