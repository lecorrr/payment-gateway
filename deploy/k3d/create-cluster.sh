#!/usr/bin/env bash
# Creates the local k3d cluster of cluster.yaml, which the dev and staging environments run on, and waits until
# Traefik is up to take their Ingresses. Needs docker, k3d, kubectl and helm; the README says how to install them.
#
#   deploy/k3d/create-cluster.sh
set -euo pipefail

cd "$(dirname "$0")"
cluster=payment-gateway
context="k3d-$cluster"
http_port=8280

for tool in docker k3d kubectl helm; do
    if ! command -v "$tool" > /dev/null; then
        echo "$tool is not installed; the README's Kubernetes section says where to get it" >&2
        exit 1
    fi
done

if k3d cluster get "$cluster" > /dev/null 2>&1; then
    echo "The cluster $cluster is there already (k3d cluster delete $cluster removes it)"
    exit 0
fi

# k3d would stop half way through with Docker's error about the port.
if ss -Htln "sport = :$http_port" | grep -q .; then
    echo "Port $http_port is taken. Pick another in cluster.yaml, and in ingress.port of" >&2
    echo "deploy/helm/payment-gateway/values-dev.yaml and values-staging.yaml" >&2
    exit 1
fi

k3d cluster create --config cluster.yaml
kubectl --context "$context" wait --for=condition=Ready nodes --all --timeout=3m

# k3s installs Traefik with a Helm job of its own, a little after the nodes are ready.
for _ in $(seq 90); do
    if kubectl --context "$context" -n kube-system get deployment traefik > /dev/null 2>&1; then
        break
    fi
    sleep 2
done
kubectl --context "$context" -n kube-system rollout status deployment/traefik --timeout=3m

echo "The cluster $cluster is ready; deploy/k3d/deploy.sh dev puts the dev environment on it"
