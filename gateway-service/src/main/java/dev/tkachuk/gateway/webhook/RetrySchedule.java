package dev.tkachuk.gateway.webhook;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When an event that wasn't delivered is tried again: after each of retry-delays in turn, plus up to a tenth more at
 * random, so the events of an endpoint that was down don't all come back at the same moment. After the last delay
 * the event is given up.
 */
final class RetrySchedule {

    private static final double MAX_JITTER = 0.1;

    private final List<Duration> delays;

    RetrySchedule(List<Duration> delays) {
        this.delays = List.copyOf(delays);
    }

    /**
     * attemptsMade counts the attempt that just failed; random is in [0, 1). Empty when that was the last attempt.
     */
    Optional<Instant> nextAttemptAt(int attemptsMade, Instant now, double random) {
        if (attemptsMade < 1) {
            throw new IllegalArgumentException("A retry follows an attempt; attemptsMade was " + attemptsMade);
        }
        if (random < 0 || random >= 1) {
            throw new IllegalArgumentException("random must be in [0, 1), got " + random);
        }
        if (attemptsMade > delays.size()) {
            return Optional.empty();
        }
        Duration delay = delays.get(attemptsMade - 1);
        long jitterMillis = (long) (delay.toMillis() * MAX_JITTER * random);
        return Optional.of(now.plus(delay).plusMillis(jitterMillis));
    }

    /** How many attempts an event gets at most: the first one and a retry after each delay. */
    int maxAttempts() {
        return delays.size() + 1;
    }
}
