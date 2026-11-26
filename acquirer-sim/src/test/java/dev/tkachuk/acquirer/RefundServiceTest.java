package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.acquirer.RefundService.Kind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Refunds of charges, with a clock the test moves: what is left of a charge, replays by reference, the card's
 * decision, and the monobank way of finishing later. The delay is long, so only the test's clock finishes refunds.
 */
class RefundServiceTest {

    private static final Duration REFUND_DELAY = Duration.ofMinutes(1);

    private final TestClock clock = new TestClock(Instant.parse("2026-11-25T10:00:00Z"));
    private final SimProperties properties = new SimProperties("test-acquirer-key-0123456789",
            "http://sim.test", Duration.ZERO, Duration.ofMinutes(15), 100, REFUND_DELAY);
    private final List<Object> events = new ArrayList<>();
    private final ChargeService charges = new ChargeService(new ChargeStore(properties), properties, clock,
            events::add);
    private final RefundService refunds = new RefundService(new RefundStore(properties), charges, properties, clock,
            events::add);

    @AfterEach
    void stopRefunds() {
        refunds.stopFinisher();
    }

    @Test
    void shouldRefundApprovedChargeAtOnce() {
        Charge charge = charge("ref-1", "4111111111111111");

        RefundService.Result result = refunds.refund(new RefundRequest(charge.id(), "refund-1", 5000L), false);

        assertThat(result.kind()).isEqualTo(Kind.CREATED);
        Refund refund = result.refund();
        assertThat(refund.id()).matches("re_[0-9a-f]{24}");
        assertThat(refund.status()).isEqualTo(Refund.SUCCEEDED);
        assertThat(refund.chargeId()).isEqualTo(charge.id());
        assertThat(refund.currency()).isEqualTo("UAH");
        assertThat(refunds.find("refund-1")).contains(refund);
        assertThat(refunds.leftOf(charge)).isEqualTo(10000);
    }

    @Test
    void shouldRefundInPartsUntilNothingIsLeft() {
        Charge charge = charge("ref-1", "4111111111111111");

        assertThat(refunds.refund(new RefundRequest(charge.id(), "refund-1", 10000L), false).kind())
                .isEqualTo(Kind.CREATED);
        assertThat(refunds.refund(new RefundRequest(charge.id(), "refund-2", 5001L), false).kind())
                .isEqualTo(Kind.TOO_LARGE);
        assertThat(refunds.refund(new RefundRequest(charge.id(), "refund-3", 5000L), false).kind())
                .isEqualTo(Kind.CREATED);

        assertThat(refunds.leftOf(charge)).isZero();
        assertThat(refunds.ofCharge(charge.id())).extracting(Refund::reference)
                .containsExactly("refund-1", "refund-3");
    }

    @Test
    void shouldAnswerRepeatWithStoredRefund() {
        Charge charge = charge("ref-1", "4111111111111111");
        Refund first = refunds.refund(new RefundRequest(charge.id(), "refund-1", 5000L), false).refund();

        RefundService.Result repeat = refunds.refund(new RefundRequest(charge.id(), "refund-1", 5000L), false);
        RefundService.Result different = refunds.refund(new RefundRequest(charge.id(), "refund-1", 4000L), false);

        assertThat(repeat.kind()).isEqualTo(Kind.REPLAYED);
        assertThat(repeat.refund()).isEqualTo(first);
        assertThat(different.kind()).isEqualTo(Kind.CONFLICT);
        assertThat(refunds.leftOf(charge)).isEqualTo(10000);
    }

    @Test
    void shouldRefundOnlyApprovedChargeItHas() {
        Charge declined = charge("ref-1", "4000000000000002");

        assertThat(refunds.refund(new RefundRequest(declined.id(), "refund-1", 5000L), false).kind())
                .isEqualTo(Kind.NOT_REFUNDABLE);
        assertThat(refunds.refund(new RefundRequest("ch_unknown", "refund-2", 5000L), false).kind())
                .isEqualTo(Kind.NO_CHARGE);
    }

    // A declined refund gives its amount back: the rest can still be refunded, and declined again.
    @Test
    void shouldDeclineRefundOfCardThatRefusesRefunds() {
        Charge charge = charge("ref-1", Issuer.REFUND_DECLINED_CARD);

        Refund refund = refunds.refund(new RefundRequest(charge.id(), "refund-1", 15000L), false).refund();

        assertThat(refund.status()).isEqualTo(Refund.FAILED);
        assertThat(refund.failureCode()).isEqualTo(Issuer.REFUND_DECLINED);
        assertThat(refunds.leftOf(charge)).isEqualTo(15000);
    }

    @Test
    void shouldFinishRefundSentTheMonobankWayLater() {
        Charge charge = charge("ref-1", "4111111111111111");

        Refund refund = refunds.refund(new RefundRequest(charge.id(), "refund-1", 5000L), true).refund();

        assertThat(refund.status()).isEqualTo(Refund.PENDING);
        assertThat(refunds.leftOf(charge)).isEqualTo(10000);
        assertThat(refunds.find("refund-1").orElseThrow().isPending()).isTrue();
        assertThat(events).isEmpty();

        clock.advance(REFUND_DELAY);

        Refund finished = refunds.find("refund-1").orElseThrow();
        assertThat(finished.status()).isEqualTo(Refund.SUCCEEDED);
        assertThat(finished.updatedAt()).isAfter(finished.createdAt());
        assertThat(events).containsExactly(new RefundChanged(finished));
        assertThat(refunds.find("refund-1")).contains(finished);
        assertThat(events).hasSize(1);
    }

    @Test
    void shouldDeclineRefundSentTheMonobankWayOnlyWhenItFinishes() {
        Charge charge = charge("ref-1", Issuer.REFUND_DECLINED_CARD);

        Refund refund = refunds.refund(new RefundRequest(charge.id(), "refund-1", 5000L), true).refund();
        clock.advance(REFUND_DELAY);

        assertThat(refund.status()).isEqualTo(Refund.PENDING);
        assertThat(refund.failureCode()).isNull();
        assertThat(refunds.ofCharge(charge.id())).singleElement()
                .satisfies(finished -> assertThat(finished.failureCode()).isEqualTo(Issuer.REFUND_DECLINED));
    }

    private Charge charge(String reference, String cardNumber) {
        ChargeRequest.Card card = new ChargeRequest.Card(cardNumber, 12, 2030, "123", null);
        return charges.charge(new ChargeRequest(reference, 15000L, "UAH", card, null, null)).charge();
    }
}
