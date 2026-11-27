#!/usr/bin/env bash
# Payments at rising rates through an environment's Ingress on k3d, with the Gatling simulation of load-test/: a
# warm-up, then 100, 200, 400, 800, 1600 and 3200 payments/s for two minutes each, until the knee, the first rate whose
# p99 passes the budget (500 ms) or whose failures pass 1 %: the run stops as soon as that is certain, and prints each
# rate's p50, p95 and p99. LOAD_STEPS, LOAD_STEP_SECONDS and LOAD_P99_BUDGET_MS change those. Every 10 s it also notes
# how many gateway pods were ready, as staging's autoscaler adds them under load, each app's CPU, as metrics-server
# sees it, and how busy the machine's CPUs were, since Gatling and the whole cluster share them; at the end it prints
# the most of each. The payments stay in the environment, as a merchant's would. Gatling's whole output goes to a log,
# and its HTML report into load-test/target/gatling.
# Usage: deploy/k3d/load-test.sh [staging|dev], staging by default. Needs kubectl and a JDK for ./mvnw.
set -euo pipefail

echo "load-test of payment-gateway on k3d"

env="${1:-staging}"
if [[ "$env" != dev && "$env" != staging ]]; then
    echo "usage: deploy/k3d/load-test.sh [staging|dev]" >&2
    exit 2
fi

repo="$(cd "$(dirname "$0")/../.." && pwd)"
context=k3d-payment-gateway
namespace="payment-gateway-$env"
log="$(mktemp "/tmp/load-test-$env-XXXXXX")"

kc() {
    kubectl --context "$context" --namespace "$namespace" "$@"
}

# The CPU time the machine has spent in all and idle since it started, in clock ticks, from /proc/stat's first line:
# user, nice, system, idle, iowait, irq, softirq and steal, of which idle and iowait are idle.
cpu_ticks() {
    awk '$1 == "cpu" { total = 0; for (i = 2; i <= 9; i++) total += $i; print total, $5 + $6; exit }' /proc/stat
}

# One line per app, "app <name> <millicores>": the CPU of all its pods together, by the pods' app label.
app_cpu() {
    local apps top
    apps="$(kc get pods --no-headers \
        -o custom-columns='POD:.metadata.name,APP:.metadata.labels.app\.kubernetes\.io/name')" || return 0
    top="$(kc top pods --no-headers)" || return 0
    awk 'NR == FNR { app[$1] = $2; next }
         ($1 in app) { cpu = $2; if (cpu ~ /m$/) sub(/m$/, "", cpu); else cpu *= 1000; sum[app[$1]] += cpu }
         END { for (name in sum) printf "app %s %d\n", name, sum[name] }' \
        <(printf '%s\n' "$apps") <(printf '%s\n' "$top")
}

# Every app ready first: a pod still starting would be measured as the gateway being slow.
if ! kc wait --for=condition=Available deployment --all --timeout=120s >> "$log" 2>&1; then
    echo "FAIL not every app in $namespace is ready: kubectl -n $namespace get pods; log in $log" >&2
    exit 1
fi
key="$(kc get secret payment-gateway -o jsonpath='{.data.GATEWAY_DEMO_MERCHANT_API_KEY}' | base64 -d)"
if [[ -z "$key" ]]; then
    echo "FAIL the Secret payment-gateway in $namespace has no GATEWAY_DEMO_MERCHANT_API_KEY" >&2
    exit 1
fi

# Every 10 s while the run goes on: "pods <ready gateways>", the apps' CPU and "machine <percent busy>".
samples="$(mktemp)"
(
    read -r total_before idle_before < <(cpu_ticks)
    while true; do
        sleep 10
        ready="$(kc get deployment gateway -o jsonpath='{.status.readyReplicas}' 2> /dev/null)" \
            && echo "pods ${ready:-0}"
        # A sample that fails is skipped, not the end of the sampling.
        app_cpu || true
        read -r total idle < <(cpu_ticks)
        if (( total > total_before )); then
            echo "machine $(( 100 * (total - total_before - (idle - idle_before)) / (total - total_before) ))"
        fi
        total_before=$total
        idle_before=$idle
    done
) >> "$samples" 2> /dev/null &
sampler=$!
trap 'kill "$sampler" 2> /dev/null || true; rm -f "$samples"' EXIT

echo "Gatling's output goes to $log"
set +e
(cd "$repo" && GATEWAY_URL="http://gateway.$env.localhost:8280" GATEWAY_API_KEY="$key" \
    ./mvnw --batch-mode --no-transfer-progress -f load-test/pom.xml gatling:test) 2>&1 \
    | tee -a "$log" \
    | grep --line-buffered -E '^load-test: |^\||: (true|false) \(actual|please open the following file|^\[ERROR\]'
status="${PIPESTATUS[0]}"
set -e

kill "$sampler" 2> /dev/null || true
wait "$sampler" 2> /dev/null || true
read -r fewest most < <(awk '$1 == "pods" && $2 ~ /^[0-9]+$/ { print $2 }' "$samples" | sort -n | sed -n '1p;$p' \
    | tr '\n' ' ') || true
if [[ -n "${fewest:-}" ]]; then
    echo "load-test: gateway pods ready during the run: from $fewest to ${most:-$fewest}"
fi
busiest="$(awk '$1 == "machine" && (!found || $2 > most) { most = $2; found = 1 } END { if (found) print most }' \
    "$samples")"
if [[ -n "$busiest" ]]; then
    echo "load-test: the machine's $(nproc) CPU threads were at most $busiest % busy, Gatling's own work included"
fi
apps="$(awk '$1 == "app" && $3 > most[$2] { most[$2] = $3 } END { for (name in most) print name, most[name] }' \
    "$samples" | sort -k2,2nr -k1,1)"
if [[ -n "$apps" ]]; then
    echo "load-test: the most CPU each app used at once, all its pods together:"
    while read -r name millicores; do
        printf 'load-test:   %-16s %6dm\n' "$name" "$millicores"
    done <<< "$apps"
else
    echo "load-test: no CPU figures for the apps: kubectl top pods gave none; is metrics-server running?"
fi
if (( status != 0 )); then
    echo "FAIL Gatling ended with $status: the lowest rate missed its budget or the run broke off; log in $log" >&2
fi
exit "$status"
