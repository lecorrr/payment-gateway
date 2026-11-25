package dev.tkachuk.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tkachuk.gateway.provider.ProviderResult.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderResultTest {

    private static final String ACTION_URL = "http://localhost:8091/challenge/ch_1";

    @Test
    void shouldCarryActionUrlWithRequiresAction() {
        ProviderResult result = ProviderResult.requiresAction("ch_1", ACTION_URL);

        assertThat(result.outcome()).isEqualTo(Outcome.REQUIRES_ACTION);
        assertThat(result.providerReference()).isEqualTo("ch_1");
        assertThat(result.actionUrl()).isEqualTo(ACTION_URL);
    }

    @Test
    void shouldKeepProviderReferenceWhilePending() {
        ProviderResult result = ProviderResult.pending("p2_1");

        assertThat(result.outcome()).isEqualTo(Outcome.PENDING);
        assertThat(result.providerReference()).isEqualTo("p2_1");
        assertThat(result.actionUrl()).isNull();
    }

    @Test
    void shouldTellUnavailableProviderFromOtherFailures() {
        assertThat(ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE).isUnavailable()).isTrue();
        assertThat(ProviderResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST).isUnavailable()).isFalse();
        assertThat(ProviderResult.unknown().isUnavailable()).isFalse();
    }

    // What counts against a provider's circuit: a decline or a refused request is the provider working.
    @Test
    void shouldCountOnlyMissingAnswersAndUnavailabilityAsProviderFailures() {
        assertThat(ProviderResult.unknown().isProviderFailure()).isTrue();
        assertThat(ProviderResult.failed(FailureCodes.PROVIDER_UNAVAILABLE).isProviderFailure()).isTrue();
        assertThat(List.of(ProviderResult.approved("ch_1"), ProviderResult.declined("ch_1", "do_not_honor"),
                ProviderResult.failed(FailureCodes.PROVIDER_REJECTED_REQUEST), ProviderResult.pending("p2_1"),
                ProviderResult.requiresAction("ch_1", ACTION_URL), ProviderResult.notFound()))
                .noneMatch(ProviderResult::isProviderFailure);
    }

    @Test
    void shouldHaveActionUrlWithRequiresActionAndNothingElse() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderResult(Outcome.REQUIRES_ACTION, "ch_1", null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderResult(Outcome.APPROVED, "ch_1", null, ACTION_URL));
    }
}
