package dev.tkachuk.loadtest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Each rate's payments: the response times of those that succeeded and the number that failed, to print a row per
 * rate and the knee when the run ends, and to tell the simulation when the knee is known, so that it stops there.
 * Gatling's HTML report has the same per rate; this is the table the README takes. Gatling's threads record at the
 * same time, so each step records under its own lock.
 */
final class StepStats {

    /** One rate's results; the percentiles are of the successful payments, by the nearest-rank method. */
    record Row(int rate, int payments, int failed, long p50, long p95, long p99, long max) {

        double failedPercent() {
            return payments == 0 ? 0 : failed * 100.0 / payments;
        }
    }

    private final Map<Integer, Step> steps;
    private final long stepSeconds;
    private final long p99BudgetMs;
    private final double maxFailedPercent;

    StepStats(List<Integer> rates, Duration stepDuration, Duration p99Budget, double maxFailedPercent) {
        Map<Integer, Step> byRate = new LinkedHashMap<>();
        rates.forEach(rate -> byRate.put(rate, new Step()));
        this.steps = Collections.unmodifiableMap(byRate);
        this.stepSeconds = stepDuration.toSeconds();
        this.p99BudgetMs = p99Budget.toMillis();
        this.maxFailedPercent = maxFailedPercent;
    }

    /** A payment at the rate: its response time, or null when no answer came, and whether it succeeded. */
    void record(int rate, Integer responseTimeMs, boolean succeeded) {
        step(rate).record(responseTimeMs, succeeded, p99BudgetMs);
    }

    /**
     * Whether the knee is known when a payment of the step at the rate is about to start: a step before it ended past
     * the p99 budget or the failure limit, or this step can no longer pass, as more of its payments have failed, or
     * taken longer than the budget, than its whole length allows. The steps run one after another, so the ones before
     * have ended.
     */
    boolean kneeFound(int rate) {
        Step current = step(rate);
        for (Map.Entry<Integer, Step> entry : steps.entrySet()) {
            Step step = entry.getValue();
            if (step == current) {
                return current.cannotPass(expectedPayments(rate), maxFailedPercent);
            }
            if (step.endedPastLimits(entry.getKey(), this)) {
                return true;
            }
        }
        throw new IllegalStateException("No step at " + rate + " payments/s");
    }

    /** The rows of the rates that ran, in order: a run that stopped at the knee has none for the rates after it. */
    List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        steps.forEach((rate, step) -> {
            Row row = step.row(rate);
            if (row.payments() > 0) {
                rows.add(row);
            }
        });
        return rows;
    }

    /** The first rate whose p99 passes the budget or whose failures pass the limit; none when every rate kept up. */
    Optional<Row> knee() {
        return kneeOf(rows());
    }

    /**
     * The table and the knee, as Markdown for the README, from one look at the stats: the run prints it once stopped,
     * while answers that were already on their way are still being recorded.
     */
    String report() {
        List<Row> rows = rows();
        StringBuilder report = new StringBuilder()
                .append("| Rate (payments/s) | Payments | Failed | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            report.append(String.format(Locale.ROOT, "| %d | %d | %d | %s | %s | %s | %s |\n", row.rate(),
                    row.payments(), row.failed(), time(row.p50()), time(row.p95()), time(row.p99()), time(row.max())));
        }
        if (rows.isEmpty()) {
            return report.append("load-test: no payment of a step was made\n").toString();
        }
        Optional<Row> knee = kneeOf(rows);
        if (knee.isEmpty()) {
            return report.append(String.format(Locale.ROOT,
                    "load-test: no knee up to %d payments/s: p99 stayed within %d ms and failures within %.1f %%\n",
                    rows.getLast().rate(), p99BudgetMs, maxFailedPercent)).toString();
        }
        Row row = knee.get();
        report.append(String.format(Locale.ROOT,
                "load-test: the knee is at %d payments/s: p99 %s ms against a budget of %d ms, %.2f %% failed\n",
                row.rate(), time(row.p99()), p99BudgetMs, row.failedPercent()));
        long expected = expectedPayments(row.rate());
        if (row.payments() < expected) {
            report.append(String.format(Locale.ROOT,
                    "load-test: the run stopped once that was certain, after %d of the step's %d payments\n",
                    row.payments(), expected));
        }
        List<Integer> notRun = steps.keySet().stream().filter(rate -> rate > row.rate()).toList();
        if (!notRun.isEmpty()) {
            report.append(String.format(Locale.ROOT, "load-test: %s payments/s did not run, being past the knee\n",
                    notRun));
        }
        return report.toString();
    }

    private Optional<Row> kneeOf(List<Row> rows) {
        return rows.stream().filter(this::isPastLimits).findFirst();
    }

    private boolean isPastLimits(Row row) {
        return row.p99() > p99BudgetMs || row.failedPercent() > maxFailedPercent;
    }

    private long expectedPayments(int rate) {
        return rate * stepSeconds;
    }

    private Step step(int rate) {
        Step step = steps.get(rate);
        if (step == null) {
            throw new IllegalArgumentException("No step at " + rate + " payments/s");
        }
        return step;
    }

    // -1 stands for a rate with no successful payment, which has no response times.
    private static String time(long ms) {
        return ms < 0 ? "-" : Long.toString(ms);
    }

    private static final class Step {

        private final List<Integer> succeededMs = new ArrayList<>();
        private int payments;
        private int failed;
        private int slow;
        private Boolean endedPastLimits;

        synchronized void record(Integer responseTimeMs, boolean succeeded, long p99BudgetMs) {
            payments++;
            if (succeeded && responseTimeMs != null) {
                succeededMs.add(responseTimeMs);
                if (responseTimeMs > p99BudgetMs) {
                    slow++;
                }
            } else {
                failed++;
            }
        }

        // The step's p99 passes the budget once more than 1 % of all its payments are slower than that, and its
        // failures pass the limit once more of them than the limit's share of all its payments have failed, whatever
        // the payments still to come do.
        synchronized boolean cannotPass(long expectedPayments, double maxFailedPercent) {
            return slow * 100L > expectedPayments || failed * 100.0 > maxFailedPercent * expectedPayments;
        }

        // Worked out once: a step that has ended records nothing more.
        synchronized boolean endedPastLimits(int rate, StepStats stats) {
            if (endedPastLimits == null) {
                endedPastLimits = stats.isPastLimits(row(rate));
            }
            return endedPastLimits;
        }

        synchronized Row row(int rate) {
            List<Integer> sorted = new ArrayList<>(succeededMs);
            Collections.sort(sorted);
            return new Row(rate, payments, failed, percentile(sorted, 50), percentile(sorted, 95),
                    percentile(sorted, 99), sorted.isEmpty() ? -1 : sorted.getLast());
        }

        private static long percentile(List<Integer> sorted, int percent) {
            if (sorted.isEmpty()) {
                return -1;
            }
            int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
            return sorted.get(Math.max(rank, 1) - 1);
        }
    }
}
