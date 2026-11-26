package dev.tkachuk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.ledger.support.EventContract;
import dev.tkachuk.ledger.support.EventContract.Example;
import dev.tkachuk.ledger.support.Events;
import dev.tkachuk.ledger.support.LedgerIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * Events from a real Kafka into the ledger's tables and out of its API: a payment and its refund as balanced
 * entries, entries of one moment in the order they were booked, an event that comes twice booked once, other
 * statuses taken but not booked, an event the ledger can't read sent to the dead-letter topic while the next one is
 * booked, a booking that fails tried again until it works, the contract's own examples, and a merchant's balances.
 */
class LedgerIT extends LedgerIntegrationTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MeterRegistry meters;

    @Test
    void shouldBookSucceededPaymentAsOwedByProviderToMerchant() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        send(Events.record(eventId, "payment.succeeded", paymentId,
                Events.paymentData(paymentId, merchantId, "SUCCEEDED", 15000, "UAH", "monobank")));

        awaitTaken(eventId);

        getPayment(paymentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.provider").value("monobank"))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.paid").value(15000))
                .andExpect(jsonPath("$.refunded").value(0))
                .andExpect(jsonPath("$.net").value(15000))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].id").value(eventId.toString()))
                .andExpect(jsonPath("$.entries[0].kind").value("PAYMENT"))
                .andExpect(jsonPath("$.entries[0].occurredAt").value(Events.OCCURRED_AT))
                .andExpect(jsonPath("$.entries[0].postings[0].account").value("provider:monobank"))
                .andExpect(jsonPath("$.entries[0].postings[0].amount").value(15000))
                .andExpect(jsonPath("$.entries[0].postings[1].account").value("merchant:" + merchantId))
                .andExpect(jsonPath("$.entries[0].postings[1].amount").value(-15000));
    }

    @Test
    void shouldTakeRefundBackFromItsPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID refundEventId = UUID.randomUUID();
        send(Events.record(UUID.randomUUID(), "payment.succeeded", paymentId,
                Events.paymentData(paymentId, merchantId, "SUCCEEDED", 15000, "UAH", "acquirer-sim")));
        send(Events.record(refundEventId, "refund.succeeded", paymentId,
                Events.refundData(refundId, paymentId, merchantId, "SUCCEEDED", 5000, "UAH", "acquirer-sim")));

        awaitTaken(refundEventId);

        getPayment(paymentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(15000))
                .andExpect(jsonPath("$.refunded").value(5000))
                .andExpect(jsonPath("$.net").value(10000))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[1].kind").value("REFUND"))
                .andExpect(jsonPath("$.entries[1].refundId").value(refundId.toString()))
                .andExpect(jsonPath("$.entries[1].postings[0].account").value("merchant:" + merchantId))
                .andExpect(jsonPath("$.entries[1].postings[0].amount").value(5000))
                .andExpect(jsonPath("$.entries[1].postings[1].account").value("provider:acquirer-sim"))
                .andExpect(jsonPath("$.entries[1].postings[1].amount").value(-5000));
    }

    // Every event here carries one time, so the payment and its refund happened at the same moment: they come in the
    // order they were booked, even when the refund's id sorts first.
    @Test
    void shouldListEntriesOfOneMomentInTheOrderTheyWereBooked() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        List<UUID> eventIds = idsInDatabaseOrder();
        UUID refundEventId = eventIds.get(0);
        UUID paymentEventId = eventIds.get(1);
        send(Events.record(paymentEventId, "payment.succeeded", paymentId,
                Events.paymentData(paymentId, merchantId, "SUCCEEDED", 15000, "UAH", "monobank")));
        send(Events.record(refundEventId, "refund.succeeded", paymentId,
                Events.refundData(UUID.randomUUID(), paymentId, merchantId, "SUCCEEDED", 5000, "UAH", "monobank")));

        awaitTaken(refundEventId);

        getPayment(paymentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].id").value(paymentEventId.toString()))
                .andExpect(jsonPath("$.entries[1].id").value(refundEventId.toString()));
    }

    // Kafka delivers at least once: the same event, by its CloudEvents id, changes the ledger once.
    @Test
    void shouldBookEventThatComesTwiceOnce() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String data = Events.paymentData(paymentId, UUID.randomUUID(), "SUCCEEDED", 15000, "UAH", "monobank");
        double duplicatesBefore = duplicatesCounted();
        // A later event of the same payment, on the same partition: once it is taken, both copies were read.
        UUID laterEventId = UUID.randomUUID();
        send(Events.record(eventId, "payment.succeeded", paymentId, data));
        send(Events.record(eventId, "payment.succeeded", paymentId, data));
        send(Events.record(laterEventId, "refund.failed", paymentId,
                Events.refundData(UUID.randomUUID(), paymentId, UUID.randomUUID(), "FAILED", 5000, "UAH", "monobank")));

        awaitTaken(laterEventId);

        getPayment(paymentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.net").value(15000));
        assertThat(duplicatesCounted() - duplicatesBefore).isEqualTo(1);
    }

    @Test
    void shouldTakeOtherStatusesWithoutBookingThem() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        send(Events.record(eventId, "payment.declined", paymentId,
                Events.paymentData(paymentId, UUID.randomUUID(), "DECLINED", 15000, "UAH", "acquirer-sim")));

        awaitTaken(eventId);

        getPayment(paymentId)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Nothing is booked for payment " + paymentId));
    }

    // The partition goes on past an event the ledger can't read, which waits on the dead-letter topic with why.
    @Test
    void shouldSendEventItCannotReadToDeadLetterTopicAndGoOn() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID badEventId = UUID.randomUUID();
        UUID nextEventId = UUID.randomUUID();
        send(Events.record(badEventId, "payment.succeeded", paymentId,
                Events.paymentData(paymentId, UUID.randomUUID(), "SUCCEEDED", 0, "UAH", "monobank")));
        send(Events.record(nextEventId, "payment.succeeded", paymentId,
                Events.paymentData(paymentId, UUID.randomUUID(), "SUCCEEDED", 15000, "UAH", "monobank")));

        awaitTaken(nextEventId);

        assertThat(taken(badEventId)).isFalse();
        List<ConsumerRecord<String, String>> deadLetters = new ArrayList<>();
        await().atMost(WAIT).until(() -> {
            deadLetters.addAll(deadLettersOf(badEventId.toString()));
            return !deadLetters.isEmpty();
        });
        ConsumerRecord<String, String> deadLetter = deadLetters.getFirst();
        assertThat(deadLetter.key()).isEqualTo(paymentId.toString());
        assertThat(Events.header(deadLetter, "ce_type")).isEqualTo("payment.succeeded");
        assertThat(Events.header(deadLetter, "kafka_dlt-original-topic")).isEqualTo("payment-events");
        assertThat(Events.header(deadLetter, "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo(MalformedEventException.class.getName());
        assertThat(Events.header(deadLetter, "kafka_dlt-exception-message")).contains("amount that isn't positive");
    }

    // Anything else, here the database refusing the entry, is tried again: skipping it would lose a payment.
    @Test
    void shouldTryAgainUntilBookingWorks() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.execute("ALTER TABLE journal_entries ADD CONSTRAINT ledger_it_refuses CHECK (amount <> 4242)");
        try {
            send(Events.record(eventId, "payment.succeeded", paymentId,
                    Events.paymentData(paymentId, UUID.randomUUID(), "SUCCEEDED", 4242, "UAH", "monobank")));

            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> !taken(eventId));
        } finally {
            jdbc.execute("ALTER TABLE journal_entries DROP CONSTRAINT IF EXISTS ledger_it_refuses");
        }

        awaitTaken(eventId);
        getPayment(paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.net").value(4242));
        assertThat(deadLettersOf(eventId.toString())).isEmpty();
    }

    @Test
    void shouldBookContractsExamples() throws Exception {
        EventContract contract = EventContract.read();
        List<Example> examples = new ArrayList<>(contract.examples("paymentEvent"));
        examples.addAll(contract.examples("refundEvent"));
        for (Example example : examples) {
            send(Events.record(String.valueOf(example.payload().get("paymentId")),
                    jsonMapper.writeValueAsString(example.payload()), example.headers()));
        }

        for (Example example : examples) {
            awaitTaken(UUID.fromString(example.headers().get("ce_id")));
        }

        // payment.succeeded and refund.succeeded of one payment; payment.declined of another.
        getPayment(UUID.fromString("0d7f5e2a-8c41-4c1b-9a6e-5b3f2d1c0e9a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value("3b9d6c4e-1f2a-4e5b-8c7d-9e0f1a2b3c4d"))
                .andExpect(jsonPath("$.provider").value("monobank"))
                .andExpect(jsonPath("$.paid").value(15000))
                .andExpect(jsonPath("$.refunded").value(5000))
                .andExpect(jsonPath("$.net").value(10000));
        getPayment(UUID.fromString("9e8d7c6b-5a49-4382-9170-6f5e4d3c2b1a")).andExpect(status().isNotFound());
    }

    @Test
    void shouldShowMerchantsBalanceInEachCurrency() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID inDollars = UUID.randomUUID();
        List<UUID> lastEvents = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        send(Events.record(UUID.randomUUID(), "payment.succeeded", first,
                Events.paymentData(first, merchantId, "SUCCEEDED", 15000, "UAH", "monobank")));
        send(Events.record(lastEvents.get(0), "refund.succeeded", first,
                Events.refundData(UUID.randomUUID(), first, merchantId, "SUCCEEDED", 5000, "UAH", "monobank")));
        send(Events.record(lastEvents.get(1), "payment.succeeded", second,
                Events.paymentData(second, merchantId, "SUCCEEDED", 2500, "UAH", "acquirer-sim")));
        send(Events.record(lastEvents.get(2), "payment.succeeded", inDollars,
                Events.paymentData(inDollars, merchantId, "SUCCEEDED", 1000, "USD", "acquirer-sim")));

        lastEvents.forEach(this::awaitTaken);

        getBalances(merchantId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.balances.length()").value(2))
                .andExpect(jsonPath("$.balances[0].currency").value("UAH"))
                .andExpect(jsonPath("$.balances[0].payable").value(12500))
                .andExpect(jsonPath("$.balances[0].paid").value(17500))
                .andExpect(jsonPath("$.balances[0].refunded").value(5000))
                .andExpect(jsonPath("$.balances[0].payments").value(2))
                .andExpect(jsonPath("$.balances[0].refunds").value(1))
                .andExpect(jsonPath("$.balances[1].currency").value("USD"))
                .andExpect(jsonPath("$.balances[1].payable").value(1000));
        getBalances(UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances.length()").value(0));
    }

    @Test
    void shouldAnswerBadPaymentIdWithProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void shouldExposeLedgerMetricsForPrometheus() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        send(Events.record(eventId, "payment.succeeded", paymentId,
                Events.paymentData(paymentId, UUID.randomUUID(), "SUCCEEDED", 15000, "UAH", "monobank")));
        awaitTaken(eventId);

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(metrics)
                .containsPattern("ledger_events_total\\{[^}]*outcome=\"booked\"[^}]*type=\"payment.succeeded\"[^}]*} [1-9]")
                .contains("ledger_dead_letters_total")
                .contains("spring_kafka_listener_seconds_bucket")
                .contains("kafka_consumer_fetch_manager_records_lag_max");
    }

    // Two random ids as PostgreSQL sorts them: it compares a uuid's bytes, as its text compares, and not as
    // UUID.compareTo does, which compares two signed longs.
    private static List<UUID> idsInDatabaseOrder() {
        List<UUID> ids = new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID()));
        ids.sort(Comparator.comparing(UUID::toString));
        return ids;
    }

    private double duplicatesCounted() {
        return meters.counter(PaymentEventListener.EVENTS, "outcome", "duplicate", "type", "payment.succeeded")
                .count();
    }
}
