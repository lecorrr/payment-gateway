package dev.tkachuk.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReceivedEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final String PAYMENT = "7f1c2b3a-0000-4000-8000-000000000001";

    private final ReceivedEvents events = new ReceivedEvents(
            new MerchantSimProperties(WebhookVerifierTest.SECRET, Duration.ofMinutes(5), 0, 2));

    @Test
    void shouldTakeWebhookOnceHoweverOftenItComes() {
        assertThat(events.countDelivery("evt-1")).isEqualTo(1);
        assertThat(events.take("evt-1", "payment.succeeded", PAYMENT, null, "SUCCEEDED", NOW, NOW)).isTrue();
        assertThat(events.countDelivery("evt-1")).isEqualTo(2);
        assertThat(events.take("evt-1", "payment.succeeded", PAYMENT, null, "SUCCEEDED", NOW, NOW.plusSeconds(1)))
                .isFalse();

        assertThat(events.all()).singleElement().satisfies(event -> {
            assertThat(event.deliveries()).isEqualTo(2);
            assertThat(event.takenAt()).isEqualTo(NOW);
        });
    }

    // Webhooks may come in any order; the latest change of the payment decides.
    @Test
    void shouldGoByWhenPaymentChangedNotWhenWebhookCame() {
        events.countDelivery("evt-2");
        events.take("evt-2", "payment.succeeded", PAYMENT, null, "SUCCEEDED", NOW.plusSeconds(5), NOW);
        events.countDelivery("evt-1");
        events.take("evt-1", "payment.requires_action", PAYMENT, null, "REQUIRES_ACTION", NOW, NOW.plusSeconds(1));

        assertThat(events.statusOf(PAYMENT)).contains("SUCCEEDED");
        assertThat(events.statusOf("another-payment")).isEmpty();
    }

    // A refund that failed later leaves the payment SUCCEEDED: its event is about the refund.
    @Test
    void shouldKeepRefundsEventsOutOfPaymentsStatus() {
        events.countDelivery("evt-1");
        events.take("evt-1", "payment.succeeded", PAYMENT, null, "SUCCEEDED", NOW, NOW);
        events.countDelivery("evt-2");
        events.take("evt-2", "refund.failed", PAYMENT, "refund-1", "FAILED", NOW.plusSeconds(5), NOW);

        assertThat(events.statusOf(PAYMENT)).contains("SUCCEEDED");
        assertThat(events.all().getFirst().refundId()).isEqualTo("refund-1");
    }

    @Test
    void shouldKnowNothingOfWebhookNotTakenYet() {
        events.countDelivery("evt-1");

        assertThat(events.statusOf(PAYMENT)).isEmpty();
        assertThat(events.all()).singleElement().satisfies(event -> assertThat(event.isTaken()).isFalse());
    }

    @Test
    void shouldForgetOldestBeyondLimitAndListNewestFirst() {
        for (String id : new String[] {"evt-1", "evt-2", "evt-3"}) {
            events.countDelivery(id);
        }

        assertThat(events.all()).extracting(ReceivedEvents.Event::webhookId).containsExactly("evt-3", "evt-2");
    }
}
