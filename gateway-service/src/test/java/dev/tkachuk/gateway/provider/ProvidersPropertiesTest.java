package dev.tkachuk.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tkachuk.gateway.provider.ProvidersProperties.CircuitSettings;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class ProvidersPropertiesTest {

    private static final CircuitSettings CIRCUIT = new CircuitSettings(10, 5, 50, Duration.ofSeconds(30), 2);

    @Test
    void shouldStripNames() {
        assertThat(new ProvidersProperties(List.of(" monobank", "acquirer-sim "), CIRCUIT).order())
                .containsExactly("monobank", "acquirer-sim");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldNeedProviders(List<String> order) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProvidersProperties(order, CIRCUIT))
                .withMessageContaining("GATEWAY_PROVIDERS");
    }

    @Test
    void shouldRefuseEmptyName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProvidersProperties(List.of("acquirer-sim", " "), CIRCUIT))
                .withMessageContaining("empty name");
    }

    @Test
    void shouldRefuseProviderNamedTwice() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProvidersProperties(List.of("monobank", "acquirer-sim", "monobank "), CIRCUIT))
                .withMessageContaining("names monobank twice");
    }

    @Test
    void shouldNeedCircuitSettings() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProvidersProperties(List.of("acquirer-sim"), null))
                .withMessageContaining("circuit-breaker");
    }

    @ParameterizedTest
    @CsvSource({
            "0,  1, 50,  30, 1, window-size",
            "10, 0, 50,  30, 1, minimum-calls",
            "10, 11, 50, 30, 1, minimum-calls",
            "10, 5, 0,   30, 1, failure-rate-percent",
            "10, 5, 101, 30, 1, failure-rate-percent",
            "10, 5, 50,  0,  1, open-for",
            "10, 5, 50,  -1, 1, open-for",
            "10, 5, 50,  30, 0, trial-calls"})
    void shouldRefuseCircuitSettingsOutOfRange(int windowSize, int minimumCalls, int failureRatePercent,
            long openForSeconds, int trialCalls, String property) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CircuitSettings(windowSize, minimumCalls, failureRatePercent,
                        Duration.ofSeconds(openForSeconds), trialCalls))
                .withMessageContaining("gateway.providers.circuit-breaker." + property);
    }

    @Test
    void shouldNeedOpenFor() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CircuitSettings(10, 5, 50, null, 2))
                .withMessageContaining("open-for");
    }
}
