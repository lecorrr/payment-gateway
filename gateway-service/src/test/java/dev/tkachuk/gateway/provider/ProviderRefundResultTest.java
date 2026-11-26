package dev.tkachuk.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.gateway.common.Currency;
import dev.tkachuk.gateway.provider.ProviderRefundResult.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderRefundResultTest {

    @Test
    void shouldCarryCodeWithFailure() {
        ProviderRefundResult failed = ProviderRefundResult.failed("re_1", FailureCodes.REFUND_DECLINED);

        assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(failed.providerReference()).isEqualTo("re_1");
        assertThat(failed.code()).isEqualTo(FailureCodes.REFUND_DECLINED);
    }

    @Test
    void shouldRefuseFailureWithoutCode() {
        assertThatThrownBy(() -> new ProviderRefundResult(Outcome.FAILED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    void shouldRefuseCodeWithAnythingButFailure(Outcome outcome) {
        assertThatThrownBy(() -> new ProviderRefundResult(outcome, null, "refund_declined"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void shouldRefuseRefundOfNothing(long amount) {
        assertThatThrownBy(() -> new RefundRequest(UUID.randomUUID(), UUID.randomUUID(), "ch_1", amount, Currency.UAH))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
