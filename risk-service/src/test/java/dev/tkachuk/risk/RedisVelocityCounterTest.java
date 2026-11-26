package dev.tkachuk.risk;

import static dev.tkachuk.risk.RedisVelocityCounter.COUNT;
import static dev.tkachuk.risk.RedisVelocityCounter.KEY_PREFIX;
import static dev.tkachuk.risk.RedisVelocityCounter.PAUSE_AFTER_FAILURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tkachuk.risk.support.MovableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The arguments of the count script, and what happens while Redis can't be reached. RiskServiceIT runs it. */
class RedisVelocityCounterTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private final MovableClock clock = new MovableClock(NOW);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisVelocityCounter counter = new RedisVelocityCounter(redis, clock);

    @Test
    void shouldCountAttemptForItsCardWithinWindow() {
        when(countCall(NOW)).thenReturn(4L);

        assertThat(counter.count("tok_1", "merchant-1/order-1", WINDOW)).hasValue(4);
    }

    @Test
    void shouldHaveNoCountWhileRedisIsDown() {
        when(countCall(NOW)).thenThrow(new RedisConnectionFailureException("Unable to connect to localhost:6379"));

        assertThat(counter.count("tok_1", "merchant-1/order-1", WINDOW)).isEmpty();
    }

    // A payment shouldn't wait for Redis's timeout again and again.
    @Test
    void shouldLeaveRedisAloneForPauseAfterFailure() {
        when(countCall(NOW)).thenThrow(new RedisConnectionFailureException("Unable to connect to localhost:6379"));
        counter.count("tok_1", "merchant-1/order-1", WINDOW);
        clock.move(PAUSE_AFTER_FAILURE.minusSeconds(1));

        assertThat(counter.count("tok_1", "merchant-1/order-1", WINDOW)).isEmpty();
        verify(redis, times(1)).execute(eq(COUNT), eq(List.of(KEY_PREFIX + "tok_1")), eq(millis(NOW)),
                eq(millis(NOW.minus(WINDOW))), eq(millis(WINDOW)), eq("merchant-1/order-1"));
    }

    @Test
    void shouldAskRedisAgainAfterPause() {
        when(countCall(NOW)).thenThrow(new RedisConnectionFailureException("Unable to connect to localhost:6379"));
        counter.count("tok_1", "merchant-1/order-1", WINDOW);
        Instant later = NOW.plus(PAUSE_AFTER_FAILURE);
        when(countCall(later)).thenReturn(1L);

        clock.move(PAUSE_AFTER_FAILURE);

        assertThat(counter.count("tok_1", "merchant-1/order-1", WINDOW)).isEqualTo(OptionalLong.of(1));
    }

    private Long countCall(Instant now) {
        return redis.execute(eq(COUNT), eq(List.of(KEY_PREFIX + "tok_1")), eq(millis(now)),
                eq(millis(now.minus(WINDOW))), eq(millis(WINDOW)), eq("merchant-1/order-1"));
    }

    private static String millis(Instant instant) {
        return Long.toString(instant.toEpochMilli());
    }

    private static String millis(Duration duration) {
        return Long.toString(duration.toMillis());
    }
}
