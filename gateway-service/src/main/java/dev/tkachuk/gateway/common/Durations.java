package dev.tkachuk.gateway.common;

import java.time.Duration;
import java.util.Locale;

/** Durations for log lines. */
public final class Durations {

    private Durations() {
    }

    /** PT30S as 30s, PT1M30S as 1m30s: as application.yml writes them. */
    public static String readable(Duration duration) {
        return duration.toString().substring(2).toLowerCase(Locale.ROOT);
    }
}
