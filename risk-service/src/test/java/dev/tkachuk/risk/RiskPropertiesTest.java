package dev.tkachuk.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tkachuk.risk.RiskProperties.Velocity;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RiskPropertiesTest {

    private static final Velocity VELOCITY = new Velocity(10, Duration.ofMinutes(10));

    @Test
    void shouldTakeCurrenciesInAnyCase() {
        RiskProperties properties = new RiskProperties(VELOCITY, Map.of("uah", 10_000_000L, " USD ", 300_000L),
                Set.of(" 400009 "));

        assertThat(properties.amountLimits()).containsOnly(Map.entry("UAH", 10_000_000L), Map.entry("USD", 300_000L));
        assertThat(properties.blockedBins()).containsExactly("400009");
    }

    @Test
    void shouldHaveNoLimitsNorBlockedRangesWhenNoneAreSet() {
        RiskProperties properties = new RiskProperties(VELOCITY, null, null);

        assertThat(properties.amountLimits()).isEmpty();
        assertThat(properties.blockedBins()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UA", "UAHH", "U1H", ""})
    void shouldRefuseLimitForWhatIsNoCurrency(String currency) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RiskProperties(VELOCITY, Map.of(currency, 100L), Set.of()))
                .withMessageContaining("risk.amount-limits");
    }

    @Test
    void shouldRefuseLimitBelowOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RiskProperties(VELOCITY, Map.of("UAH", 0L), Set.of()))
                .withMessageContaining("risk.amount-limits.UAH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"40000", "4000099", "40000a", ""})
    void shouldRefuseBlockedRangeThatIsNoSixDigits(String bin) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RiskProperties(VELOCITY, Map.of(), Set.of(bin)))
                .withMessageContaining("risk.blocked-bins");
    }

    @Test
    void shouldNeedVelocity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RiskProperties(null, Map.of(), Set.of()))
                .withMessageContaining("risk.velocity");
    }

    @Test
    void shouldRefuseVelocityThatAllowsNothing() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Velocity(0, Duration.ofMinutes(10)))
                .withMessageContaining("risk.velocity.max-payments");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Velocity(10, Duration.ZERO))
                .withMessageContaining("risk.velocity.window");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Velocity(10, null))
                .withMessageContaining("risk.velocity.window");
    }
}
