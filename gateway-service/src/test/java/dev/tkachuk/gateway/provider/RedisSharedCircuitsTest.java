package dev.tkachuk.gateway.provider;

import static dev.tkachuk.gateway.provider.RedisSharedCircuits.KEY_PREFIX;
import static dev.tkachuk.gateway.provider.RedisSharedCircuits.PAUSE_AFTER_FAILURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tkachuk.gateway.support.MovableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** The Redis commands behind the shared circuits, and what happens while Redis can't be reached. */
class RedisSharedCircuitsTest {

    private static final Duration OPEN_FOR = Duration.ofSeconds(30);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-25T10:00:00Z"));
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisSharedCircuits circuits = new RedisSharedCircuits(redis, clock);

    @BeforeEach
    void connectValues() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void shouldReadEveryProviderInOneCall() {
        when(values.multiGet(List.of(KEY_PREFIX + "monobank", KEY_PREFIX + "acquirer-sim")))
                .thenReturn(Arrays.asList("2026-11-25T09:59:50Z", null));

        assertThat(circuits.openOnes(List.of("monobank", "acquirer-sim"))).containsExactly("monobank");
    }

    @Test
    void shouldMarkCircuitOpenUntilItsOpenTimeIsOver() {
        circuits.markOpen("monobank", OPEN_FOR);

        verify(values).set(KEY_PREFIX + "monobank", "2026-11-25T10:00:00Z", OPEN_FOR);
    }

    @Test
    void shouldRemoveMarkWhenCircuitCloses() {
        circuits.clear("monobank");

        verify(redis).delete(KEY_PREFIX + "monobank");
    }

    @Test
    void shouldSeeNoOpenCircuitsWhileRedisIsDown() {
        redisIsDown();

        assertThat(circuits.openOnes(List.of("monobank"))).isEmpty();
    }

    // A payment shouldn't wait for Redis's timeout again and again.
    @Test
    void shouldLeaveRedisAloneForPauseAfterFailure() {
        redisIsDown();
        circuits.openOnes(List.of("monobank"));
        clock.move(PAUSE_AFTER_FAILURE.minusSeconds(1));

        assertThat(circuits.openOnes(List.of("monobank"))).isEmpty();
        circuits.markOpen("monobank", OPEN_FOR);
        circuits.clear("monobank");

        verify(values, times(1)).multiGet(anyList());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void shouldAskRedisAgainAfterPause() {
        when(values.multiGet(anyList()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to localhost:6379"))
                .thenReturn(Arrays.asList("2026-11-25T10:00:00Z"));
        circuits.openOnes(List.of("monobank"));

        clock.move(PAUSE_AFTER_FAILURE);

        assertThat(circuits.openOnes(List.of("monobank"))).containsExactly("monobank");
    }

    private void redisIsDown() {
        when(values.multiGet(anyList()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to localhost:6379"));
    }
}
