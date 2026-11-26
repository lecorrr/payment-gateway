package dev.tkachuk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.ledger.Ledger.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Each record goes to the ledger once read, and is counted by what came of it. */
class PaymentEventListenerTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RecordingLedger ledger = new RecordingLedger();
    private final PaymentEventListener listener =
            new PaymentEventListener(new EventReader(JsonMapper.builder().build()), ledger, meters);

    @Test
    void shouldHandEventToLedgerWithWhereItWasRead() {
        ledger.answer = Outcome.NOT_BOOKED;

        listener.onEvent(record("payment.declined", UUID.randomUUID().toString()));

        assertThat(ledger.taken).hasSize(1);
        assertThat(ledger.sources).containsExactly(new RecordSource("payment-events", 2, 7));
        assertThat(count("not_booked", "payment.declined")).isEqualTo(1);
    }

    @Test
    void shouldCountDuplicatesApart() {
        ledger.answer = Outcome.DUPLICATE;

        listener.onEvent(record("refund.failed", UUID.randomUUID().toString()));

        assertThat(count("duplicate", "refund.failed")).isEqualTo(1);
    }

    // A sender can't make a series per type it makes up.
    @Test
    void shouldCountTypesOutsideContractAsOther() {
        ledger.answer = Outcome.NOT_BOOKED;

        listener.onEvent(record("payment.disputed", UUID.randomUUID().toString()));

        assertThat(count("not_booked", "other")).isEqualTo(1);
        assertThat(PaymentEventListener.typeTag("payment.succeeded")).isEqualTo("payment.succeeded");
    }

    // The error handler decides what then happens to it; the ledger never sees it.
    @Test
    void shouldLeaveRecordItCannotReadToErrorHandler() {
        assertThatThrownBy(() -> listener.onEvent(record("payment.declined", "not-a-payment-id")))
                .isInstanceOf(MalformedEventException.class);

        assertThat(ledger.taken).isEmpty();
        assertThat(meters.find(PaymentEventListener.EVENTS).counters()).isEmpty();
    }

    private double count(String outcome, String type) {
        return meters.counter(PaymentEventListener.EVENTS, "outcome", outcome, "type", type).count();
    }

    private static ConsumerRecord<String, String> record(String type, String key) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-events", 2, 7, key, "{}");
        record.headers().add("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_type", type.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** A ledger that books nothing, notes what it was given and answers as the test says. */
    private static final class RecordingLedger extends Ledger {

        private final List<LedgerEvent> taken = new ArrayList<>();
        private final List<RecordSource> sources = new ArrayList<>();
        private Outcome answer = Outcome.BOOKED;

        RecordingLedger() {
            super(null, Clock.systemUTC());
        }

        @Override
        public Outcome take(LedgerEvent event, RecordSource source) {
            taken.add(event);
            sources.add(source);
            return answer;
        }
    }
}
