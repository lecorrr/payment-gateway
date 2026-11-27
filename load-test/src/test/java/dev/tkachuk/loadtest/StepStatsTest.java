package dev.tkachuk.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StepStatsTest {

    // Steps of 4 s, so a step at 25 payments/s makes 100 payments and one at 50 makes 200.
    private static final Duration STEP = Duration.ofSeconds(4);
    private static final Duration BUDGET = Duration.ofMillis(500);
    private static final int FAST_MS = 40;
    private static final int SLOW_MS = 700;

    private static StepStats statsOf(int... rates) {
        return new StepStats(IntStream.of(rates).boxed().toList(), STEP, BUDGET, 1.0);
    }

    @Test
    void shouldGiveThePercentilesOfTheSuccessfulPaymentsByNearestRank() {
        StepStats stats = statsOf(25);
        for (int ms = 100; ms >= 1; ms--) {
            stats.record(25, ms, true);
        }

        assertThat(stats.rows()).containsExactly(new StepStats.Row(25, 100, 0, 50, 95, 99, 100));
    }

    @Test
    void shouldCountAFailureWhetherAnAnswerCameOrNot() {
        StepStats stats = statsOf(25);
        stats.record(25, 20, true);
        stats.record(25, 30, false);
        stats.record(25, null, false);

        StepStats.Row row = stats.rows().getFirst();
        assertThat(row.payments()).isEqualTo(3);
        assertThat(row.failed()).isEqualTo(2);
        // Only the successful payment's time counts.
        assertThat(row.p99()).isEqualTo(20);
    }

    @Test
    void shouldFindTheKneeAtTheFirstRateWhoseP99PassesTheBudget() {
        StepStats stats = statsOf(25, 50, 100);
        record(stats, 25, 100, FAST_MS);
        record(stats, 50, 200, 600);
        record(stats, 100, 400, 900);

        assertThat(stats.knee()).map(StepStats.Row::rate).contains(50);
    }

    @Test
    void shouldFindTheKneeWhereFailuresPassTheLimitEvenIfTheSuccessfulOnesWereFast() {
        StepStats stats = statsOf(25, 50);
        record(stats, 25, 100, FAST_MS);
        record(stats, 50, 197, FAST_MS);
        fail(stats, 50, 3);

        assertThat(stats.knee()).map(StepStats.Row::rate).contains(50);
    }

    @Test
    void shouldFindNoKneeWhenEveryRateKeptUp() {
        StepStats stats = statsOf(25, 50);
        record(stats, 25, 100, FAST_MS);
        record(stats, 50, 200, 499);

        assertThat(stats.knee()).isEmpty();
        assertThat(stats.report()).contains("no knee up to 50 payments/s");
    }

    @Test
    void shouldPrintARowPerRateAndTheKnee() {
        StepStats stats = statsOf(25, 50);
        record(stats, 25, 100, FAST_MS);
        record(stats, 50, 200, SLOW_MS);

        List<String> lines = stats.report().lines().toList();
        assertThat(lines).hasSize(5);
        assertThat(lines.get(2)).isEqualTo("| 25 | 100 | 0 | 40 | 40 | 40 | 40 |");
        assertThat(lines.get(3)).isEqualTo("| 50 | 200 | 0 | 700 | 700 | 700 | 700 |");
        assertThat(lines.get(4)).startsWith("load-test: the knee is at 50 payments/s: p99 700 ms");
    }

    @Test
    void shouldShowNoTimesForARateWithoutASuccessfulPayment() {
        StepStats stats = statsOf(25);
        fail(stats, 25, 1);

        assertThat(stats.report()).contains("| 25 | 1 | 1 | - | - | - | - |");
    }

    @Test
    void shouldGoOnWhileTheStepCanStillPass() {
        StepStats stats = statsOf(25);
        // 1 % of the step's 100 payments may be slow and 1 % may fail.
        record(stats, 25, 1, SLOW_MS);
        fail(stats, 25, 1);

        assertThat(stats.kneeFound(25)).isFalse();
    }

    @Test
    void shouldStopOnceMoreThanOnePercentOfTheStepIsSlowerThanTheBudget() {
        StepStats stats = statsOf(25);
        record(stats, 25, 2, SLOW_MS);

        assertThat(stats.kneeFound(25)).isTrue();
        // However fast the rest of the step would have been, its p99 passes the budget.
        record(stats, 25, 98, FAST_MS);
        assertThat(stats.knee()).map(StepStats.Row::rate).contains(25);
    }

    @Test
    void shouldStopOnceMoreOfTheStepHasFailedThanTheLimitAllows() {
        StepStats stats = statsOf(25);
        fail(stats, 25, 2);

        assertThat(stats.kneeFound(25)).isTrue();
        record(stats, 25, 98, FAST_MS);
        assertThat(stats.knee()).map(StepStats.Row::rate).contains(25);
    }

    @Test
    void shouldStopAtTheNextStepWhenTheOneBeforeEndedPastTheBudget() {
        StepStats stats = statsOf(25, 50);
        // One failure and one slow payment don't decide the step while it runs, but at its end the slow one is the
        // p99 of the 99 that succeeded.
        fail(stats, 25, 1);
        record(stats, 25, 1, SLOW_MS);
        assertThat(stats.kneeFound(25)).isFalse();
        record(stats, 25, 98, FAST_MS);

        assertThat(stats.kneeFound(50)).isTrue();
    }

    @Test
    void shouldGoOnToTheNextStepWhenTheOneBeforeKeptUp() {
        StepStats stats = statsOf(25, 50);
        record(stats, 25, 100, FAST_MS);

        assertThat(stats.kneeFound(50)).isFalse();
    }

    @Test
    void shouldReportWhereARunThatStoppedAtTheKneeEnded() {
        StepStats stats = statsOf(25, 50, 100);
        record(stats, 25, 100, FAST_MS);
        record(stats, 50, 10, FAST_MS);
        record(stats, 50, 3, SLOW_MS);

        assertThat(stats.rows()).extracting(StepStats.Row::rate).containsExactly(25, 50);
        assertThat(stats.report())
                .contains("the knee is at 50 payments/s")
                .contains("after 13 of the step's 200 payments")
                .contains("[100] payments/s did not run");
    }

    @Test
    void shouldReportFromOneLookAtTheStatsWhilePaymentsAreStillRecorded() throws InterruptedException {
        // A step long enough that the report says how many of its payments were made before the stop.
        StepStats stats = new StepStats(List.of(25), Duration.ofSeconds(10_000), BUDGET, 1.0);
        record(stats, 25, 1_000, SLOW_MS);
        Thread recorder = Thread.ofPlatform().start(() -> record(stats, 25, 200_000, SLOW_MS));
        try {
            while (recorder.isAlive()) {
                List<String> lines = stats.report().lines().toList();
                String payments = lines.get(2).split("\\|")[2].strip();
                assertThat(lines.get(4)).contains("after " + payments + " of the step's 250000 payments");
            }
        } finally {
            recorder.join();
        }
    }

    @Test
    void shouldSayWhenNoStepMadeAPayment() {
        assertThat(statsOf(25).report()).contains("no payment of a step was made");
    }

    @Test
    void shouldRefuseARateItHasNoStepFor() {
        StepStats stats = statsOf(25);
        assertThatIllegalArgumentException().isThrownBy(() -> stats.record(30, 10, true)).withMessageContaining("30");
        assertThatIllegalArgumentException().isThrownBy(() -> stats.kneeFound(30)).withMessageContaining("30");
    }

    @Test
    void shouldTakePaymentsFromManyThreadsAtOnce() throws InterruptedException {
        StepStats stats = statsOf(25);
        List<Thread> threads = IntStream.range(0, 8)
                .mapToObj(t -> Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 1_000; i++) {
                        stats.record(25, 10, true);
                        stats.kneeFound(25);
                    }
                }))
                .toList();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(stats.rows().getFirst().payments()).isEqualTo(8_000);
    }

    private static void record(StepStats stats, int rate, int payments, int ms) {
        for (int i = 0; i < payments; i++) {
            stats.record(rate, ms, true);
        }
    }

    private static void fail(StepStats stats, int rate, int payments) {
        for (int i = 0; i < payments; i++) {
            stats.record(rate, null, false);
        }
    }
}
