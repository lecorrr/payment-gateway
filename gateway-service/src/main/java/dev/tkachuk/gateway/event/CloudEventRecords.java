package dev.tkachuk.gateway.event;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

/**
 * An outbox event as a Kafka record, in the binary mode of the CloudEvents Kafka binding: the data is the record's
 * value, the event's attributes are ce_ headers, and the key is the payment's id, so all of a payment's events, its
 * refunds' too, go to one partition and are read in the order they were written.
 */
final class CloudEventRecords {

    static final String SPEC_VERSION = "1.0";
    static final String SOURCE = "/payment-gateway";

    private CloudEventRecords() {
    }

    static ProducerRecord<String, String> recordOf(String topic, OutboxEvent event) {
        List<Header> headers = new ArrayList<>();
        headers.add(header("ce_specversion", SPEC_VERSION));
        headers.add(header("ce_id", event.id().toString()));
        headers.add(header("ce_source", SOURCE));
        headers.add(header("ce_type", event.type()));
        headers.add(header("ce_time", event.createdAt().toString()));
        headers.add(header("ce_subject", event.paymentId().toString()));
        headers.add(header("content-type", "application/json"));
        String traceparent = event.traceparent();
        if (traceparent != null) {
            headers.add(header(EventOutbox.TRACEPARENT, traceparent));
        }
        // No timestamp: Kafka gives the record the time it is sent; ce_time is when the change was made.
        return new ProducerRecord<>(topic, null, null, event.paymentId().toString(), event.payload(), headers);
    }

    private static Header header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
