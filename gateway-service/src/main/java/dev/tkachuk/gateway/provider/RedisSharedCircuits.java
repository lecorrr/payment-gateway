package dev.tkachuk.gateway.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SharedCircuits in Redis: one key per open circuit, which expires when the circuit's open time is over, holding
 * when it opened. Redis that can't be reached is left alone for PAUSE_AFTER_FAILURE, so payments don't wait on it
 * again and again; meanwhile every instance goes by its own circuits.
 */
@Component
class RedisSharedCircuits implements SharedCircuits {

    static final String KEY_PREFIX = "payment-gateway:open-circuit:";
    static final Duration PAUSE_AFTER_FAILURE = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RedisSharedCircuits.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    // Null while Redis answers.
    @Nullable
    private Instant pausedUntil;

    RedisSharedCircuits(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public Set<String> openOnes(List<String> providers) {
        if (providers.isEmpty() || isPaused()) {
            return Set.of();
        }
        List<String> marks;
        try {
            marks = redis.opsForValue().multiGet(providers.stream().map(RedisSharedCircuits::keyOf).toList());
        } catch (DataAccessException e) {
            failed(e);
            return Set.of();
        }
        answered();
        Set<String> open = new HashSet<>();
        // Redis answers MGET with one value per key, in the keys' order; a key that doesn't exist is null.
        for (int i = 0; marks != null && i < providers.size(); i++) {
            if (marks.get(i) != null) {
                open.add(providers.get(i));
            }
        }
        return open;
    }

    @Override
    public void markOpen(String provider, Duration openFor) {
        if (isPaused()) {
            return;
        }
        try {
            redis.opsForValue().set(keyOf(provider), clock.instant().toString(), openFor);
        } catch (DataAccessException e) {
            failed(e);
            return;
        }
        answered();
    }

    @Override
    public void clear(String provider) {
        if (isPaused()) {
            return;
        }
        try {
            redis.delete(keyOf(provider));
        } catch (DataAccessException e) {
            failed(e);
            return;
        }
        answered();
    }

    static String keyOf(String provider) {
        return KEY_PREFIX + provider;
    }

    private synchronized boolean isPaused() {
        return pausedUntil != null && clock.instant().isBefore(pausedUntil);
    }

    private synchronized void failed(DataAccessException e) {
        if (pausedUntil == null) {
            log.warn("Redis can't be reached ({}); open circuits aren't shared with other instances until it is back",
                    e.getMessage());
        }
        pausedUntil = clock.instant().plus(PAUSE_AFTER_FAILURE);
    }

    private synchronized void answered() {
        if (pausedUntil != null) {
            log.info("Redis answers again; open circuits are shared with other instances again");
            pausedUntil = null;
        }
    }
}
