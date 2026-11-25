#!/usr/bin/env bash
# Prints the line coverage of each module from its JaCoCo CSV report; the GitLab pipeline reads it from the log.
# Usage: bash ci/print-coverage.sh <module>...
set -euo pipefail

if [[ $# -eq 0 ]]; then
    echo "Usage: bash ci/print-coverage.sh <module>..." >&2
    exit 2
fi

for module in "$@"; do
    report="$module/target/site/jacoco/jacoco.csv"
    if [[ ! -s "$report" ]]; then
        echo "No coverage report at $report: run ./mvnw verify first" >&2
        exit 1
    fi
    # Columns 8 and 9 are the lines missed and covered of each class.
    awk -F, -v module="$module" '
        NR > 1 { missed += $8; covered += $9 }
        END {
            if (missed + covered == 0) {
                print module ": the coverage report has no lines" > "/dev/stderr"
                exit 1
            }
            printf "%s line coverage: %.1f%%\n", module, 100 * covered / (missed + covered)
        }' "$report"
done
