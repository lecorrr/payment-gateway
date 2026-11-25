package dev.tkachuk.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.card.CardExpiry;
import dev.tkachuk.gateway.card.CardNumber;
import dev.tkachuk.gateway.card.Cvc;
import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.provider.ProvidersProperties.CircuitSettings;
import dev.tkachuk.gateway.support.MovableClock;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Routing with real Resilience4j circuits, over a window of 4 charges: 2 of them, half failing, open a circuit for
 * 30 s, after which 1 trial charge decides.
 */
class ProviderRouterTest {

    private static final Duration OPEN_FOR = Duration.ofSeconds(30);
    private static final UUID PAYMENT_ID = UUID.fromString("7f1c2b3a-0000-4000-8000-000000000001");
    private static final Card CARD = new Card(CardNumber.parse("4111111111111111"), CardExpiry.of(12, 2030),
            new Cvc("123"), "OLENA KOVALENKO");
    private static final ChargeRequest REQUEST = new ChargeRequest(PAYMENT_ID, 15000, Currency.UAH, CARD,
            "Two tickets", URI.create("http://gateway.test/3ds/return/any"));

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-25T10:00:00Z"));
    private final FakeConnector monobank = new FakeConnector("monobank");
    private final FakeConnector sim = new FakeConnector("acquirer-sim");
    private final FakeSharedCircuits shared = new FakeSharedCircuits();
    private final ProviderRouter router = router(monobank, sim);

    @Test
    void shouldRouteToPreferredProvider() {
        assertThat(routedTo()).isSameAs(monobank);
    }

    @Test
    void shouldLeaveOutProvidersAlreadyTried() {
        assertThat(router.next(Set.of("monobank")).orElseThrow().provider()).isSameAs(sim);
        assertThat(router.next(Set.of("monobank", "acquirer-sim"))).isEmpty();
    }

    @Test
    void shouldSkipProviderAnotherInstanceFoundFailing() {
        shared.markOpen("monobank", OPEN_FOR);

        assertThat(routedTo()).isSameAs(sim);
    }

    @Test
    void shouldOpenCircuitOfUnavailableProvider() {
        monobank.answering(ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE));

        charge();
        charge();

        assertThat(routedTo()).isSameAs(sim);
        assertThat(shared.openOnes(List.of("monobank"))).containsExactly("monobank");
        assertThat(shared.openedFor).containsEntry("monobank", OPEN_FOR);
    }

    @Test
    void shouldCountLostAnswersAgainstProvider() {
        monobank.answering(ProviderResult.unknown());

        charge();
        charge();

        assertThat(routedTo()).isSameAs(sim);
    }

    @Test
    void shouldCountExceptionsAgainstProvider() {
        // FakeConnector throws when it has no answer.
        assertThatIllegalStateException().isThrownBy(this::charge);
        assertThatIllegalStateException().isThrownBy(this::charge);

        assertThat(routedTo()).isSameAs(sim);
    }

    // A declined card or a refused request is the provider answering as it should.
    @Test
    void shouldNotCountDeclinesOrRefusedRequestsAgainstProvider() {
        monobank.answering(ProviderResult.declined("p2_1", "insufficient_funds"));
        charge();
        charge();
        monobank.answering(ProviderResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST));
        charge();
        charge();

        assertThat(routedTo()).isSameAs(monobank);
        assertThat(shared.openedFor).isEmpty();
    }

    @Test
    void shouldStayClosedBelowFailureRate() {
        monobank.answering(ProviderResult.approved("p2_1"));
        charge();
        charge();
        charge();
        monobank.answering(ProviderResult.unknown());
        charge();

        assertThat(routedTo()).isSameAs(monobank);
    }

    @Test
    void shouldGiveProviderTrialChargeAfterOpenFor() {
        openMonobankCircuit();
        clock.move(OPEN_FOR.plusSeconds(1));

        assertThat(routedTo()).isSameAs(monobank);
    }

    @Test
    void shouldCloseCircuitWhenTrialChargeWorks() {
        openMonobankCircuit();
        clock.move(OPEN_FOR.plusSeconds(1));
        monobank.answering(ProviderResult.approved("p2_1"));

        charge();

        assertThat(shared.openUntil).doesNotContainKey("monobank");
        assertThat(routedTo()).isSameAs(monobank);
    }

    @Test
    void shouldOpenCircuitAgainWhenTrialChargeFails() {
        openMonobankCircuit();
        clock.move(OPEN_FOR.plusSeconds(1));

        charge();

        assertThat(routedTo()).isSameAs(sim);
        assertThat(shared.marks).isEqualTo(2);
    }

    // With one trial charge allowed, a route that is never charged must give its permit back.
    @Test
    void shouldGiveBackPermitOfRouteNotCharged() {
        openMonobankCircuit();
        clock.move(OPEN_FOR.plusSeconds(1));

        router.next(Set.of()).orElseThrow().release();

        assertThat(routedTo()).isSameAs(monobank);
    }

    @Test
    void shouldHoldBackSecondTrialChargeWhileFirstIsUnderway() {
        openMonobankCircuit();
        clock.move(OPEN_FOR.plusSeconds(1));

        ProviderRoute trial = router.next(Set.of()).orElseThrow();

        assertThat(trial.provider()).isSameAs(monobank);
        assertThat(routedTo()).isSameAs(sim);
    }

    @Test
    void shouldFindNoProviderWhenEveryCircuitIsOpen() {
        openMonobankCircuit();
        sim.answering(ProviderResult.unknown());
        charge();
        charge();

        assertThat(router.next(Set.of())).isEmpty();
    }

    private void openMonobankCircuit() {
        monobank.answering(ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE));
        charge();
        charge();
        assertThat(routedTo()).as("monobank's circuit is open").isSameAs(sim);
    }

    private ProviderConnector routedTo() {
        ProviderRoute route = router.next(Set.of()).orElseThrow();
        route.release();
        return route.provider();
    }

    private ProviderResult charge() {
        return router.next(Set.of()).orElseThrow().charge(REQUEST);
    }

    private ProviderRouter router(FakeConnector... connectors) {
        List<String> order = Arrays.stream(connectors).map(FakeConnector::name).toList();
        ProvidersProperties properties = new ProvidersProperties(order, new CircuitSettings(4, 2, 50, OPEN_FOR, 1));
        ProviderConnectors providers = new ProviderConnectors(List.of(connectors), properties);
        CircuitBreakerRegistry circuitBreakers =
                new ProviderCircuitsConfiguration().providerCircuitBreakers(properties, clock);
        return new ProviderRouter(providers, circuitBreakers, shared, properties);
    }

    /** SharedCircuits in memory, with marks that run out by the test's clock as Redis keys would. */
    private final class FakeSharedCircuits implements SharedCircuits {

        private final Map<String, Instant> openUntil = new HashMap<>();
        private final Map<String, Duration> openedFor = new HashMap<>();
        private int marks;

        @Override
        public Set<String> openOnes(List<String> providers) {
            return providers.stream()
                    .filter(name -> openUntil.containsKey(name) && clock.instant().isBefore(openUntil.get(name)))
                    .collect(Collectors.toCollection(HashSet::new));
        }

        @Override
        public void markOpen(String provider, Duration openFor) {
            openUntil.put(provider, clock.instant().plus(openFor));
            openedFor.put(provider, openFor);
            marks++;
        }

        @Override
        public void clear(String provider) {
            openUntil.remove(provider);
        }
    }
}
