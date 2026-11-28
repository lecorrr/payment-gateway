package dev.tkachuk.risk;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.grpc.client.GrpcChannelFactory;

/** A warm-up that fails never stops risk-service from starting; RiskServiceIT shows one that works. */
class WarmUpTest {

    private final GrpcChannelFactory channels = mock(GrpcChannelFactory.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final WarmUp warmUp = new WarmUp(channels, redis);

    @BeforeEach
    void haveNoChannel() {
        when(channels.createChannel(WarmUp.CHANNEL)).thenThrow(new IllegalStateException("no channel"));
    }

    @Test
    void shouldLetRiskServiceStartWhenItCannotWarmUp() {
        when(redis.execute(anyCallback())).thenThrow(unreachable());

        assertThatCode(() -> warmUp.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
        verify(redis, times(WarmUp.REDIS_ATTEMPTS)).execute(anyCallback());
    }

    // The first connection took longer than the timeouts while it loaded what it needs; the next one came in time.
    @Test
    void shouldTryRedisAgainAfterItsFirstConnectionFails() {
        when(redis.execute(anyCallback())).thenThrow(unreachable()).thenReturn("PONG");

        warmUp.run(mock(ApplicationArguments.class));

        verify(redis, times(2)).execute(anyCallback());
    }

    private static RedisCallback<String> anyCallback() {
        return any();
    }

    private static RedisConnectionFailureException unreachable() {
        return new RedisConnectionFailureException("Unable to connect to Redis");
    }
}
