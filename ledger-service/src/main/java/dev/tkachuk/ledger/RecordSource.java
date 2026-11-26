package dev.tkachuk.ledger;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Where an event was read from, kept with it to trace an entry back to its record. */
record RecordSource(String topic, int partition, long offset) {

    static RecordSource of(ConsumerRecord<?, ?> record) {
        return new RecordSource(record.topic(), record.partition(), record.offset());
    }
}
