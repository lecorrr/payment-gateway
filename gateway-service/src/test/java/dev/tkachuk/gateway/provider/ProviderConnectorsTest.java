package dev.tkachuk.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderConnectorsTest {

    private final ProviderConnector sim = new FakeConnector("acquirer-sim", List.of());
    private final ProviderConnector monobank = new FakeConnector("monobank", List.of());

    @Test
    void shouldChargeNewPaymentsWithActiveProvider() {
        ProviderConnectors providers = new ProviderConnectors(List.of(sim, monobank), active("monobank"));

        assertThat(providers.active()).isSameAs(monobank);
    }

    // Payments in flight are looked up where they were charged, whichever provider is active now.
    @Test
    void shouldFindEveryProviderByName() {
        ProviderConnectors providers = new ProviderConnectors(List.of(sim, monobank), active("monobank"));

        assertThat(providers.byName("acquirer-sim")).containsSame(sim);
        assertThat(providers.byName("old-provider")).isEmpty();
    }

    @Test
    void shouldRefuseActiveProviderItHasNoConnectorFor() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderConnectors(List.of(sim, monobank), active("stripe")))
                .withMessageContaining("gateway.providers.active")
                .withMessageContaining("acquirer-sim, monobank");
    }

    @Test
    void shouldRefuseActiveProviderWithoutItsSettings() {
        ProviderConnector unconfigured = new FakeConnector("monobank", List.of("gateway.providers.monobank.token"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderConnectors(List.of(sim, unconfigured), active("monobank")))
                .withMessageContaining("gateway.providers.monobank.token");
    }

    // A provider that isn't active may lack its settings: it charges nothing.
    @Test
    void shouldAcceptInactiveProviderWithoutItsSettings() {
        ProviderConnector unconfigured = new FakeConnector("monobank", List.of("gateway.providers.monobank.token"));

        assertThat(new ProviderConnectors(List.of(sim, unconfigured), active("acquirer-sim")).active()).isSameAs(sim);
    }

    @Test
    void shouldRefuseTwoConnectorsWithOneName() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ProviderConnectors(List.of(sim, new FakeConnector("acquirer-sim", List.of())),
                        active("acquirer-sim")))
                .withMessageContaining("acquirer-sim");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldNeedActiveProvider(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProvidersProperties(name))
                .withMessageContaining("GATEWAY_PROVIDER");
    }

    private static ProvidersProperties active(String name) {
        return new ProvidersProperties(name);
    }

    private record FakeConnector(String name, List<String> missingSettings) implements ProviderConnector {

        @Override
        public ProviderResult charge(ChargeRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderResult lookup(ChargeLookup lookup) {
            throw new UnsupportedOperationException();
        }
    }
}
