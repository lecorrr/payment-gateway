package dev.tkachuk.loadtest;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Visa numbers that no payment of the run used before, as the smoke test's new cards are: risk-service declines a
 * card's eleventh payment in ten minutes, which would turn the load test into a test of that rule.
 */
final class TestCards {

    // The range of the README's Visa test card: the simulators approve every card they don't list, without 3-D Secure.
    private static final String PREFIX = "411111";
    private static final long SEQUENCES = 1_000_000_000L;

    private final AtomicLong next;

    /** Cards from the given place in the range, which a run picks at random so as not to reuse the last run's. */
    TestCards(long first) {
        this.next = new AtomicLong(first);
    }

    String nextNumber() {
        long sequence = Math.floorMod(next.getAndIncrement(), SEQUENCES);
        String digits = PREFIX + String.format(Locale.ROOT, "%09d", sequence);
        return digits + luhnDigit(digits);
    }

    /** The digit that makes the number pass the Luhn check, which the gateway makes before anything else. */
    static int luhnDigit(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return (10 - sum % 10) % 10;
    }
}
