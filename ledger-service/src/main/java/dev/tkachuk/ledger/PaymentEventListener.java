package dev.tkachuk.ledger;

import dev.tkachuk.ledger.Ledger.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads the payment-events topic one record at a time and hands each event to the ledger. Its offset is committed
 * once the ledger has taken it; an exception leaves it to the error handler (KafkaConfiguration), which tries it
 * again or, when it can't be read, sends it to the dead-letter topic.
 */
@Component
class PaymentEventListener {

    static final String EVENTS = "ledger.events";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    // The contract's types; any other is counted as "other", so a sender can't add series without end.
    private static final Set<String> KNOWN_TYPES = Set.of("payment.requires_action", "payment.succeeded",
            "payment.declined", "payment.failed", "refund.succeeded", "refund.failed");

    private final EventReader reader;
    private final Ledger ledger;
    private final MeterRegistry meters;

    PaymentEventListener(EventReader reader, Ledger ledger, MeterRegistry meters) {
        this.reader = reader;
        this.ledger = ledger;
        this.meters = meters;
    }

    @KafkaListener(topics = "${ledger.topic}")
    public void onEvent(ConsumerRecord<String, String> record) {
        LedgerEvent event = reader.read(record);
        Outcome outcome = ledger.take(event, RecordSource.of(record));
        meters.counter(EVENTS, "outcome", outcome.name().toLowerCase(Locale.ROOT), "type", typeTag(event.type()))
                .increment();
        if (outcome == Outcome.DUPLICATE) {
            log.info("Event {} ({}) was taken before; skipped its copy at offset {} of partition {}",
                    event.eventId(), event.type(), record.offset(), record.partition());
        } else {
            log.debug("Event {} ({}) of payment {}: {}", event.eventId(), event.type(), event.paymentId(), outcome);
        }
    }

    static String typeTag(String type) {
        return KNOWN_TYPES.contains(type) ? type : "other";
    }
}
