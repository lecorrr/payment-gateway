package dev.tkachuk.risk;

import java.time.Duration;
import java.util.OptionalLong;

/** How many payments a card has made lately. */
interface VelocityCounter {

    /**
     * Counts the attempt for the card, once however often it is asked about, and returns how many payments the card
     * has made within the window up to now, this one included. Empty when the count can't be had.
     */
    OptionalLong count(String cardToken, String attemptId, Duration window);
}
