#!/usr/bin/env bash
# The e2e job's after_script: what the cluster looked like at the end, saved under e2e/ for the job's artifacts, so a
# failed run can be read without running it again. The Secret is never read; the apps don't log keys.
#
#   bash ci/collect-e2e-logs.sh
set -uo pipefail

dir=e2e
context=k3d-payment-gateway
namespace=payment-gateway-dev
mkdir -p "$dir"

kc() {
    kubectl --context "$context" "$@"
}

if ! kc get nodes -o wide > "$dir/nodes.txt" 2>&1; then
    echo "collect-e2e-logs: no cluster to look at; $dir/nodes.txt says why"
    exit 0
fi
kc get all,ingress,networkpolicy,poddisruptionbudget,persistentvolumeclaim --all-namespaces -o wide \
    > "$dir/objects.txt" 2>&1
kc get events --all-namespaces --sort-by=.lastTimestamp > "$dir/events.txt" 2>&1
kc --namespace "$namespace" describe pods > "$dir/pods.txt" 2>&1
for pod in $(kc --namespace "$namespace" get pods -o name 2> /dev/null); do
    name="${pod#pod/}"
    kc --namespace "$namespace" logs "$pod" --all-containers --prefix --tail=2000 > "$dir/logs-$name.txt" 2>&1
    # A container that restarted: the run before, which is usually the one that says why.
    kc --namespace "$namespace" logs "$pod" --all-containers --prefix --tail=2000 --previous \
        > "$dir/logs-$name-previous.txt" 2> /dev/null || rm -f "$dir/logs-$name-previous.txt"
done
echo "collect-e2e-logs: the cluster's state and the pods' logs are in the job's artifacts, under $dir/"
