#!/usr/bin/env bash
# Checks an environment that deploy.sh put on the local k3d cluster, from outside, as a merchant and a customer's
# browser use it:
# - every app is ready, and every pod runs the version in pom.xml;
# - through the Ingress at http://*.ENV.localhost:8280: a payment and its replay, a refund settled by monobank-sim's
#   webhook, both webhooks at the demo shop, a 3-D Secure payment from the challenge page back to the shop's page,
#   and a payment risk-service declines;
# - the ledger booked the payment and its refund from their events on Kafka;
# - the NetworkPolicies: the gateway reaches PostgreSQL, Redis and Kafka, the ledger Kafka and its own database,
#   which the gateway can't reach, and merchant-sim reaches none of them;
# - with more than one risk-service pod (staging), every pod gets the gateways' calls;
# - with the autoscaler (staging), at least two gateways run, and a rollout of the gateway loses no payment;
# - helm test.
# It makes 20 to 200 test payments, the rollout most of them, and takes two to five minutes. Needs curl, kubectl
# and python3. The details go to a log in /tmp, or to SMOKE_TEST_LOG.
#
# The Ingress is this machine's port 8280. Where the cluster's load balancer listens somewhere else, INGRESS_ADDRESS
# says where, as HOST:PORT; the pipeline's e2e job sets docker:8280, its Docker-in-Docker service.
#
#   deploy/k3d/smoke-test.sh dev|staging
set -euo pipefail

echo "smoke-test of payment-gateway on k3d"

env="${1:-}"
if [[ "$env" != dev && "$env" != staging ]]; then
    echo "usage: deploy/k3d/smoke-test.sh dev|staging" >&2
    exit 2
fi

repo="$(cd "$(dirname "$0")/../.." && pwd)"
context=k3d-payment-gateway
namespace="payment-gateway-$env"
release=payment-gateway
port=8280
gateway="http://gateway.$env.localhost:$port"
shop="http://shop.$env.localhost:$port"
api="$gateway/api/v1"
if [[ -n "${SMOKE_TEST_LOG:-}" ]]; then
    log="$SMOKE_TEST_LOG"
    mkdir -p "$(dirname "$log")"
    : > "$log"
else
    log="$(mktemp "/tmp/smoke-test-$env-XXXXXX")"
fi
run_id="$(date +%s)"

# Every request to the Ingress goes to INGRESS_ADDRESS instead, with the same URL and Host; the others, such as the
# port-forwards' on 127.0.0.1, go where their URL says.
connect_to=()
if [[ -n "${INGRESS_ADDRESS:-}" ]]; then
    connect_to=(--connect-to ":$port:$INGRESS_ADDRESS")
fi
curl() {
    command curl "${connect_to[@]}" "$@"
}
seq=0
summary=()

kc() {
    kubectl --context "$context" --namespace "$namespace" "$@"
}

pass() { summary+=("PASS $1"); }
fail() { summary+=("FAIL $1"); }

# Passes WHAT when the command's exit status is 0, fails it with WHY otherwise.
check() {
    local what="$1" why="$2"
    shift 2
    if "$@" >> "$log" 2>&1; then
        pass "$what"
    else
        fail "$what: $why"
    fi
}

# Runs the command once a second until its exit status is 0, for at most SECONDS.
wait_until() {
    local deadline=$(( SECONDS + $1 ))
    shift
    until "$@" >> "$log" 2>&1; do
        (( SECONDS < deadline )) || return 1
        sleep 1
    done
}

# The value at the dotted PATH of the JSON on stdin, or nothing.
json_value() {
    python3 -c '
import json, sys
try:
    value = json.load(sys.stdin)
    for part in sys.argv[1].split("."):
        value = value[part]
except (ValueError, KeyError, TypeError):
    sys.exit(0)
print(value)
' "$1"
}

# The Luhn check digit of a card number's other digits.
luhn_digit() {
    local digits="$1" sum=0 i position=0 digit
    for (( i = ${#digits} - 1; i >= 0; i-- )); do
        digit="${digits:i:1}"
        if (( position % 2 == 0 )); then
            digit=$(( digit * 2 ))
            if (( digit > 9 )); then
                digit=$(( digit - 9 ))
            fi
        fi
        sum=$(( sum + digit ))
        position=$(( position + 1 ))
    done
    echo $(( (10 - sum % 10) % 10 ))
}

# Pays 150.00 UAH with CARD, or with a Visa number no payment used before when CARD is "new", so risk-service's
# limit of ten payments per card declines none of them; RETURN_URL is where the customer goes after 3-D Secure.
# Sets status (the HTTP status, 000 for no answer), body, request and idempotency_key; not called as $(...), which
# would lose them.
pay() {
    local card="$1" return_url="${2:-}" answer digits
    seq=$(( seq + 1 ))
    if [[ "$card" == new ]]; then
        digits="$(printf '411111%09d' $(( (run_id % 100000) * 10000 + seq )))"
        card="$digits$(luhn_digit "$digits")"
    fi
    idempotency_key="smoke-$run_id-$seq"
    request="{\"orderId\": \"SMOKE-$run_id-$seq\", \"amount\": 15000, \"currency\": \"UAH\""
    if [[ -n "$return_url" ]]; then
        request+=", \"returnUrl\": \"$return_url\""
    fi
    request+=", \"card\": {\"number\": \"$card\", \"expiryMonth\": 12, \"expiryYear\": 2030, \"cvc\": \"123\"}}"
    answer="$(curl -s -m 20 -w '\n%{http_code}' -X POST "$api/payments" -H "X-Api-Key: $key" \
        -H "Idempotency-Key: $idempotency_key" -H "Content-Type: application/json" -d "$request" 2>> "$log" || true)"
    status="${answer##*$'\n'}"
    body="${answer%$'\n'*}"
    echo "payment $seq: $status $body" >> "$log"
}

payment_has_status() {
    [[ "$(curl -s -m 10 "$api/payments/$1" -H "X-Api-Key: $key" | json_value status)" == "$2" ]]
}

refund_has_status() {
    [[ "$(curl -s -m 10 "$api/payments/$1/refunds/$2" -H "X-Api-Key: $key" | json_value status)" == "$3" ]]
}

# Whether the demo shop got a webhook of TYPE about the payment or refund with ID.
shop_has_event() {
    curl -s -m 10 "$shop/events" | python3 -c '
import json, sys
events = json.load(sys.stdin)
sys.exit(0 if any(sys.argv[1] in (e.get("paymentId"), e.get("refundId")) and e.get("type") == sys.argv[2]
                  for e in events) else 1)
' "$1" "$2"
}

shop_page_shows() {
    curl -s -m 10 "$shop/return?paymentId=$1" | grep -q "<b>$2</b>"
}

# What PATH answers on PORT of a pod (pod/NAME), through a port-forward of its own to a free local port.
pod_get() {
    local target="$1" pod_port="$2" path="$3" forward_log local_port="" pid
    forward_log="$(mktemp)"
    kc port-forward "$target" ":$pod_port" > "$forward_log" 2>&1 &
    pid=$!
    for _ in $(seq 50); do
        local_port="$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) .*/\1/p' "$forward_log" | head -n 1)"
        [[ -n "$local_port" ]] && break
        sleep 0.2
    done
    if [[ -n "$local_port" ]]; then
        curl -s -m 10 "http://127.0.0.1:$local_port$path" || true
    fi
    kill "$pid" 2> /dev/null || true
    wait "$pid" 2> /dev/null || true
    rm -f "$forward_log"
}

# The app's running pods, but not those already stopping: right after a rollout, the old pods still run for a
# while, though they get no new requests.
pods_of() {
    kc get pods -l "app.kubernetes.io/instance=$release,app.kubernetes.io/name=$1" \
        --field-selector=status.phase=Running \
        -o go-template='{{range .items}}{{if not .metadata.deletionTimestamp}}{{.metadata.name}} {{end}}{{end}}'
}

# The assessments a risk-service pod has counted.
assessments_of() {
    pod_get "pod/$1" 8094 /actuator/prometheus | awk '/^risk_assessments_total\{/ { sum += $NF } END { print sum + 0 }'
}

# Whether the app's TCP connection to HOST:PORT opens within 3 s, from inside its pod.
can_connect() {
    kc exec "deploy/$1" -- timeout 3 bash -c "exec 3<>/dev/tcp/$2/$3"
}

gateway_reaches_its_stores() {
    can_connect gateway postgres 5432 && can_connect gateway redis 6379 && can_connect gateway kafka 9092
}

ledger_reaches_its_stores() {
    can_connect ledger-service kafka 9092 && can_connect ledger-service ledger-postgres 5432
}

gateway_reaches_no_ledger_database() {
    ! can_connect gateway ledger-postgres 5432
}

merchant_sim_reaches_no_store() {
    ! can_connect merchant-sim postgres 5432 && ! can_connect merchant-sim redis 6379 \
        && ! can_connect merchant-sim kafka 9092 && ! can_connect merchant-sim ledger-postgres 5432
}

# Whether what the ledger booked for the payment adds up to NET, in minor units.
ledger_net_is() {
    [[ "$(pod_get deploy/ledger-service 8095 "/api/v1/payments/$1" | json_value net)" == "$2" ]]
}

# Whether any gateway pod is stopping: told to, but not gone yet.
gateway_pods_stopping() {
    [[ -n "$(kc get pods -l "app.kubernetes.io/instance=$release,app.kubernetes.io/name=gateway" \
        -o jsonpath='{.items[*].metadata.deletionTimestamp}')" ]]
}

# Whether the rollout whose rollout status runs as PID is still on: rollout status still waiting, or old pods
# still stopping.
rollout_running() {
    kill -0 "$1" 2> /dev/null || gateway_pods_stopping
}

ready_gateways_at_least() {
    local ready
    ready="$(kc get deployment gateway -o jsonpath='{.status.readyReplicas}')"
    (( ${ready:-0} >= $1 ))
}

if ! kc get namespace "$namespace" > /dev/null 2>&1; then
    echo "FAIL there is no namespace $namespace on $context: deploy/k3d/deploy.sh $env"
    exit 1
fi
key="$(kc get secret payment-gateway -o jsonpath='{.data.GATEWAY_DEMO_MERCHANT_API_KEY}' | base64 -d)"
if [[ -z "$key" ]]; then
    echo "FAIL the Secret payment-gateway in $namespace has no GATEWAY_DEMO_MERCHANT_API_KEY"
    exit 1
fi
version="$(python3 -c '
import sys, xml.etree.ElementTree as ET
print(ET.parse(sys.argv[1]).getroot().findtext("{http://maven.apache.org/POM/4.0.0}version"))
' "$repo/pom.xml")"
echo "$namespace on $context at $gateway, expecting version $version; log $log"
kc get pods -o wide >> "$log" 2>&1

# Every app is ready, in the repository's version.
not_ready=()
for workload in deployment/gateway deployment/risk-service deployment/ledger-service deployment/acquirer-sim \
    deployment/monobank-sim deployment/merchant-sim deployment/redis statefulset/postgres statefulset/ledger-postgres \
    statefulset/kafka; do
    kc rollout status "$workload" --timeout=3m >> "$log" 2>&1 || not_ready+=("$workload")
done
if (( ${#not_ready[@]} == 0 )); then
    pass "every app is ready"
else
    fail "not ready: ${not_ready[*]}"
fi

stale=()
for app in gateway:8081 risk-service:8094 ledger-service:8081 acquirer-sim:8081 monobank-sim:8081 merchant-sim:8081; do
    for pod in $(pods_of "${app%%:*}"); do
        running="$(pod_get "pod/$pod" "${app##*:}" /actuator/info | json_value build.version)"
        [[ "$running" == "$version" ]] || stale+=("$pod runs ${running:-no version it would tell}")
    done
done
if (( ${#stale[@]} == 0 )); then
    pass "every pod runs $version"
else
    fail "not every pod runs $version: ${stale[*]}"
fi

# A payment, its replay, a refund and their webhooks, through the Ingress.
pay new
paid_id="$(json_value id <<< "$body")"
if [[ "$status" == 201 && "$(json_value status <<< "$body")" == SUCCEEDED ]]; then
    pass "a payment through $gateway succeeded, at $(json_value provider <<< "$body")"
else
    fail "a payment through $gateway: HTTP $status, $body"
fi

headers="$(mktemp)"
answer="$(curl -s -m 20 -D "$headers" -w '\n%{http_code}' -X POST "$api/payments" -H "X-Api-Key: $key" \
    -H "Idempotency-Key: $idempotency_key" -H "Content-Type: application/json" -d "$request" 2>> "$log" || true)"
if [[ "${answer##*$'\n'}" == 200 && "$(json_value id <<< "${answer%$'\n'*}")" == "$paid_id" ]] \
    && grep -qi '^Idempotent-Replayed: true' "$headers"; then
    pass "the same request again got the same payment, Idempotent-Replayed"
else
    fail "the same request again: HTTP ${answer##*$'\n'}, $(tr -d '\r' < "$headers" | grep -i '^Idempotent' || true)"
fi
rm -f "$headers"

answer="$(curl -s -m 20 -w '\n%{http_code}' -X POST "$api/payments/$paid_id/refunds" -H "X-Api-Key: $key" \
    -H "Idempotency-Key: smoke-refund-$run_id" -H "Content-Type: application/json" -d '{"amount": 5000}' \
    2>> "$log" || true)"
refund_id="$(json_value id <<< "${answer%$'\n'*}")"
echo "refund: $answer" >> "$log"
if [[ "${answer##*$'\n'}" != 201 || -z "$refund_id" ]]; then
    fail "a refund of the payment: HTTP ${answer##*$'\n'}, ${answer%$'\n'*}"
# monobank-sim's webhook settles a refund in a few seconds; the gateway's own lookup would take 30 s.
elif wait_until 15 refund_has_status "$paid_id" "$refund_id" SUCCEEDED; then
    pass "a refund succeeded by monobank-sim's webhook, within 15 s"
elif wait_until 45 refund_has_status "$paid_id" "$refund_id" SUCCEEDED; then
    fail "a refund succeeded only by the gateway's lookup: monobank-sim's webhook did not get through"
else
    fail "a refund did not succeed in a minute"
fi

check "the demo shop got payment.succeeded" "not at $shop/events in 30 s" \
    wait_until 30 shop_has_event "$paid_id" payment.succeeded
check "the demo shop got refund.succeeded" "not at $shop/events in 30 s" \
    wait_until 30 shop_has_event "${refund_id:--}" refund.succeeded
# 150.00 UAH paid less 50.00 refunded, both from their events on Kafka.
check "the ledger booked the payment and its refund, net 100.00 UAH" "not in 30 s" \
    wait_until 30 ledger_net_is "$paid_id" 10000

# 3-D Secure, as the customer's browser goes through it.
pay 4000000000003220 "$shop/return"
challenge_id="$(json_value id <<< "$body")"
challenge_url="$(json_value nextAction.url <<< "$body")"
if [[ "$status" == 201 && "$(json_value status <<< "$body")" == REQUIRES_ACTION \
    && "$challenge_url" == "http://monobank.$env.localhost:$port/"* ]]; then
    pass "a 3-D Secure payment waits for its challenge, at $challenge_url"
    landed="$(curl -s -m 30 -L -o /dev/null -w '%{http_code} %{url_effective}' -d outcome=pass "$challenge_url" \
        2>> "$log" || true)"
    check "passing the challenge sends the browser back to the shop's page" "it ended at $landed" \
        test "$landed" == "200 $shop/return?paymentId=$challenge_id"
    check "the 3-D Secure payment succeeded" "not in 30 s" wait_until 30 payment_has_status "$challenge_id" SUCCEEDED
    check "the shop's page shows it succeeded" "not in 30 s" wait_until 30 shop_page_shows "$challenge_id" SUCCEEDED
else
    fail "a 3-D Secure payment: HTTP $status, $body"
fi

pay 4000090000000003
if [[ "$status" == 201 && "$(json_value status <<< "$body")" == DECLINED \
    && "$(json_value declineCode <<< "$body")" == risk_declined ]]; then
    pass "a payment from the blocked card range is declined by risk-service"
else
    fail "a payment from the blocked card range: HTTP $status, $body"
fi

# The NetworkPolicies, from inside the pods.
check "the gateway reaches PostgreSQL, Redis and Kafka" "a connection from its pod did not open" \
    gateway_reaches_its_stores
check "the ledger reaches Kafka and its own PostgreSQL" "a connection from its pod did not open" \
    ledger_reaches_its_stores
check "the gateway can't reach the ledger's PostgreSQL" "a connection from its pod opened" \
    gateway_reaches_no_ledger_database
check "merchant-sim reaches none of the databases, Redis and Kafka" "a connection from its pod opened" \
    merchant_sim_reaches_no_store

# Every risk-service pod gets calls: the gateways balance gRPC over the pods behind the headless Service.
read -r -a risk_pods <<< "$(pods_of risk-service)"
if (( ${#risk_pods[@]} > 1 )); then
    declare -A assessed_before
    for pod in "${risk_pods[@]}"; do
        assessed_before[$pod]="$(assessments_of "$pod")"
    done
    idle=("${risk_pods[@]}")
    deadline=$(( SECONDS + 120 ))
    while (( ${#idle[@]} > 0 && SECONDS < deadline )); do
        for _ in 1 2 3 4 5; do
            pay new
        done
        idle=()
        for pod in "${risk_pods[@]}"; do
            (( $(assessments_of "$pod") > ${assessed_before[$pod]} )) || idle+=("$pod")
        done
    done
    if (( ${#idle[@]} == 0 )); then
        pass "each of the ${#risk_pods[@]} risk-service pods got the gateways' calls"
    else
        fail "the gateways' calls missed ${idle[*]} for 2 minutes"
    fi
fi

# With the autoscaler: two gateways, and a rollout that loses no payment.
if kc get horizontalpodautoscaler gateway > /dev/null 2>&1; then
    check "the autoscaler runs at least two gateways" "fewer were ready for 2 minutes" \
        wait_until 120 ready_gateways_at_least 2
    kc rollout restart deployment/gateway >> "$log" 2>&1
    kc rollout status deployment/gateway --timeout=5m >> "$log" 2>&1 &
    rollout=$!
    sent=0
    lost=()
    rollout_started=$SECONDS
    # Until the old pods are gone, not only until rollout status is done: that is when they are told to stop, and
    # they still wait out their preStop and finish their requests.
    while rollout_running "$rollout" && (( SECONDS - rollout_started < 420 )); do
        pay new
        sent=$(( sent + 1 ))
        [[ "$status" == 201 ]] || lost+=("$status")
        sleep 0.5
    done
    if ! wait "$rollout"; then
        fail "the gateway's rollout did not finish in 5 minutes"
    elif gateway_pods_stopping; then
        fail "the gateway's old pods were still stopping 7 minutes after the rollout began"
    elif (( ${#lost[@]} == 0 && sent > 0 )); then
        pass "a rollout of the gateway lost none of the $sent payments of its $(( SECONDS - rollout_started )) s"
    else
        answers="$(printf '%s\n' "${lost[@]}" | sort -u | tr '\n' ' ')"
        fail "a rollout of the gateway lost ${#lost[@]} of $sent payments, answered HTTP ${answers% }"
    fi
fi

check "helm test" "it failed; its log is in $log" \
    helm --kube-context "$context" test "$release" --namespace "$namespace" --logs

echo
printf '%s\n' "${summary[@]}"
failed="$(printf '%s\n' "${summary[@]}" | grep -c '^FAIL' || true)"
echo "$(( ${#summary[@]} - failed )) passed, $failed failed; details in $log"
(( failed == 0 ))
