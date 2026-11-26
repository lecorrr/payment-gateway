package dev.tkachuk.risk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.risk.RiskProperties.Velocity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RiskRulesTest {

    private static final RiskProperties PROPERTIES = new RiskProperties(new Velocity(3, Duration.ofMinutes(10)),
            Map.of("UAH", 10_000_000L), Set.of("400009"));

    private final FakeVelocityCounter velocity = new FakeVelocityCounter();
    private final RiskRules rules = new RiskRules(PROPERTIES, velocity);

    @Test
    void shouldAllowPaymentThatBreaksNoRule() {
        velocity.answer(OptionalLong.of(1));

        assertThat(rules.assess(payment("411111", 15000, "UAH"))).isEqualTo(Assessment.allow());
    }

    @Test
    void shouldDeclineCardFromBlockedRange() {
        velocity.answer(OptionalLong.of(1));

        assertThat(rules.assess(payment("400009", 15000, "UAH")))
                .isEqualTo(Assessment.decline(Assessment.BLOCKED_CARD_RANGE));
    }

    @Test
    void shouldDeclineAmountOverItsCurrencysLimit() {
        velocity.answer(OptionalLong.of(1));

        assertThat(rules.assess(payment("411111", 10_000_000, "UAH"))).isEqualTo(Assessment.allow());
        assertThat(rules.assess(payment("411111", 10_000_001, "UAH")))
                .isEqualTo(Assessment.decline(Assessment.AMOUNT_OVER_LIMIT));
    }

    @Test
    void shouldLimitNoAmountOfCurrencyWithoutLimit() {
        velocity.answer(OptionalLong.of(1));

        assertThat(rules.assess(payment("411111", 999_999_999, "USD"))).isEqualTo(Assessment.allow());
    }

    @Test
    void shouldDeclineCardOverMaxPaymentsInWindow() {
        velocity.answer(OptionalLong.of(3));
        assertThat(rules.assess(payment("411111", 15000, "UAH"))).isEqualTo(Assessment.allow());

        velocity.answer(OptionalLong.of(4));
        assertThat(rules.assess(payment("411111", 15000, "UAH")))
                .isEqualTo(Assessment.decline(Assessment.TOO_MANY_PAYMENTS));
    }

    // Redis being down takes away one rule, not the others.
    @Test
    void shouldStillApplyOtherRulesWhenCountIsUnknown() {
        velocity.answer(OptionalLong.empty());

        assertThat(rules.assess(payment("411111", 15000, "UAH"))).isEqualTo(Assessment.allow());
        assertThat(rules.assess(payment("400009", 15000, "UAH")))
                .isEqualTo(Assessment.decline(Assessment.BLOCKED_CARD_RANGE));
    }

    @Test
    void shouldCountEveryPaymentForItsCardDeclinedOnesToo() {
        velocity.answer(OptionalLong.of(1));

        rules.assess(payment("400009", 15000, "UAH"));
        rules.assess(payment("411111", 10_000_001, "UAH"));

        assertThat(velocity.counted)
                .containsExactly("tok_1 merchant-1/order-1 PT10M", "tok_1 merchant-1/order-1 PT10M");
    }

    private static PaymentAttempt payment(String cardBin, long amount, String currency) {
        return new PaymentAttempt("merchant-1/order-1", "6f9a2c1e-0000-4000-8000-000000000001", "merchant-1", amount,
                currency, "tok_1", cardBin);
    }

    private static final class FakeVelocityCounter implements VelocityCounter {

        private final List<String> counted = new ArrayList<>();
        private OptionalLong answer = OptionalLong.empty();

        void answer(OptionalLong count) {
            this.answer = count;
        }

        @Override
        public OptionalLong count(String cardToken, String attemptId, Duration window) {
            counted.add(cardToken + " " + attemptId + " " + window);
            return answer;
        }
    }
}
