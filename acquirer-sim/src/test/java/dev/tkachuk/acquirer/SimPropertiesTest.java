package dev.tkachuk.acquirer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SimPropertiesTest {

    private static final String KEY = "test-acquirer-key-0123456789";

    @Test
    void shouldNeedLongEnoughApiKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties("short", Duration.ZERO, 10))
                .withMessageContaining("ACQUIRER_SIM_API_KEY");
        assertThatIllegalArgumentException().isThrownBy(() -> new SimProperties(null, Duration.ZERO, 10));
    }

    @Test
    void shouldRejectNegativeDelayAndEmptyStore() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(KEY, Duration.ofSeconds(-1), 10))
                .withMessageContaining("slow-delay");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SimProperties(KEY, Duration.ZERO, 0))
                .withMessageContaining("max-charges");
    }

    @Test
    void shouldHideApiKey() {
        assertThat(new SimProperties(KEY, Duration.ZERO, 10).toString()).doesNotContain(KEY);
    }
}
