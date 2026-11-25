package dev.tkachuk.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tkachuk.gateway.provider.ProviderResult.Outcome;
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
    void shouldHaveActionUrlWithRequiresActionAndNothingElse() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderResult(Outcome.REQUIRES_ACTION, "ch_1", null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProviderResult(Outcome.APPROVED, "ch_1", null, ACTION_URL));
    }
}
