package dev.tkachuk.risk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A card's payments in Redis: a sorted set per card with each attempt once, scored by when it was first counted.
 * One script, so every instance of risk-service counts the same and none sees half a change: it drops the attempts
 * older than the window, adds this one unless it is there already, keeps the set for a window more, and counts.
 * After Redis fails to answer, it is left alone for a while, so payments don't wait for its timeout again and again.
 */
@Component
class RedisVelocityCounter implements VelocityCounter {

    static final String KEY_PREFIX = "risk-service:card-payments:";
    static final Duration PAUSE_AFTER_FAILURE = Duration.ofSeconds(30);

    // KEYS[1] the card's set; ARGV[1] now, ARGV[2] the last moment before the window, ARGV[3] the window, all in
    // milliseconds; ARGV[4] the attempt.
    static final RedisScript<Long> COUNT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[2])
            redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(RedisVelocityCounter.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    // Null while Redis answers.
    @Nullable
    private Instant pausedUntil;

    RedisVelocityCounter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public OptionalLong count(String cardToken, String attemptId, Duration window) {
        if (isPaused()) {
            return OptionalLong.empty();
        }
        long now = clock.millis();
        // The window is the time since now minus window: an attempt exactly a window old is out of it.
        long beforeWindow = now - window.toMillis();
        Long count;
        try {
            count = redis.execute(COUNT, List.of(KEY_PREFIX + cardToken), Long.toString(now),
                    Long.toString(beforeWindow), Long.toString(window.toMillis()), attemptId);
        } catch (DataAccessException e) {
            failed(e);
            return OptionalLong.empty();
        }
        answered();
        if (count == null) {
            log.warn("Redis answered the card count without a number");
            return OptionalLong.empty();
        }
        return OptionalLong.of(count);
    }

    private synchronized boolean isPaused() {
        return pausedUntil != null && clock.instant().isBefore(pausedUntil);
    }

    private synchronized void failed(DataAccessException e) {
        if (pausedUntil == null) {
            log.warn("Redis can't be reached ({}); cards' payments aren't counted until it is back", e.getMessage());
        }
        pausedUntil = clock.instant().plus(PAUSE_AFTER_FAILURE);
    }

    private synchronized void answered() {
        if (pausedUntil != null) {
            log.info("Redis answers again; cards' payments are counted again");
            pausedUntil = null;
        }
    }
}
