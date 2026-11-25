package dev.tkachuk.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderConnectorsTest {

    private final ProviderConnector sim = new FakeConnector("acquirer-sim");
    private final ProviderConnector monobank = new FakeConnector("monobank");

    @Test
    void shouldKeepProvidersInConfiguredOrder() {
        ProviderConnectors providers = new ProviderConnectors(List.of(sim, monobank),
                order("monobank", "acquirer-sim"));

        assertThat(providers.ordered()).containsExactly(monobank, sim);
    }

    // Payments in flight are looked up where they were charged, whatever the order is now.
    @Test
    void shouldFindEveryProviderByName() {
        ProviderConnectors providers = new ProviderConnectors(List.of(sim, monobank), order("monobank"));

        assertThat(providers.byName("acquirer-sim")).containsSame(sim);
        assertThat(providers.byName("old-provider")).isEmpty();
    }

    @Test
    void shouldRefuseProviderItHasNoConnectorFor() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderConnectors(List.of(sim, monobank), order("acquirer-sim", "stripe")))
                .withMessageContaining("gateway.providers.order names stripe")
                .withMessageContaining("acquirer-sim, monobank");
    }

    @Test
    void shouldRefuseListedProviderWithoutItsSettings() {
        ProviderConnector unconfigured = new FakeConnector("monobank", List.of("gateway.providers.monobank.token"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderConnectors(List.of(sim, unconfigured), order("acquirer-sim", "monobank")))
                .withMessageContaining("gateway.providers.monobank.token");
    }

    // A provider that isn't in the order may lack its settings: it charges nothing.
    @Test
    void shouldAcceptUnlistedProviderWithoutItsSettings() {
        ProviderConnector unconfigured = new FakeConnector("monobank", List.of("gateway.providers.monobank.token"));

        assertThat(new ProviderConnectors(List.of(sim, unconfigured), order("acquirer-sim")).ordered())
                .containsExactly(sim);
    }

    @Test
    void shouldRefuseTwoConnectorsWithOneName() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ProviderConnectors(List.of(sim, new FakeConnector("acquirer-sim")),
                        order("acquirer-sim")))
                .withMessageContaining("acquirer-sim");
    }

    private static ProvidersProperties order(String... names) {
        return new ProvidersProperties(List.of(names),
                new ProvidersProperties.CircuitSettings(10, 5, 50, Duration.ofSeconds(30), 2));
    }
}
