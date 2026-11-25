package dev.tkachuk.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryScheduleTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private final RetrySchedule schedule = new RetrySchedule(List.of(Duration.ofSeconds(5), Duration.ofMinutes(5)));

    @Test
    void shouldWaitEachDelayInTurn() {
        assertThat(schedule.nextAttemptAt(1, NOW, 0)).contains(NOW.plusSeconds(5));
        assertThat(schedule.nextAttemptAt(2, NOW, 0)).contains(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void shouldGiveUpAfterLastDelay() {
        assertThat(schedule.nextAttemptAt(3, NOW, 0)).isEmpty();
        assertThat(schedule.maxAttempts()).isEqualTo(3);
    }

    @Test
    void shouldAddUpToTenthOfDelayAtRandom() {
        assertThat(schedule.nextAttemptAt(2, NOW, 0.5)).contains(NOW.plus(Duration.ofMinutes(5)).plusSeconds(15));
        assertThat(schedule.nextAttemptAt(2, NOW, 0.999999).orElseThrow())
                .isBefore(NOW.plus(Duration.ofMinutes(5)).plusSeconds(30));
    }

    @Test
    void shouldNeverRetryWithoutDelays() {
        RetrySchedule noRetries = new RetrySchedule(List.of());

        assertThat(noRetries.nextAttemptAt(1, NOW, 0)).isEmpty();
        assertThat(noRetries.maxAttempts()).isEqualTo(1);
    }

    @Test
    void shouldNeedAnAttemptBeforeRetry() {
        assertThatIllegalArgumentException().isThrownBy(() -> schedule.nextAttemptAt(0, NOW, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.0})
    void shouldNeedRandomFromZeroToOne(double random) {
        assertThatIllegalArgumentException().isThrownBy(() -> schedule.nextAttemptAt(1, NOW, random));
    }
}
