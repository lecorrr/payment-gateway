#!/usr/bin/env bash
# Checks the Helm chart for every environment: helm lint, then helm template with the environment's values, and what
# it renders against the schemas of Kubernetes itself and of the Prometheus Operator's ServiceMonitor (kubeconform),
# so that a values file or a template a cluster would refuse fails here. Last, values the chart must refuse, each
# with the error that says why, so the chart's schema and checks are known to work. Needs helm and kubeconform.
# Usage: bash ci/check-chart.sh [Kubernetes version, default 1.35.0]
set -euo pipefail

cd "$(dirname "$0")/.."
chart=deploy/helm/payment-gateway
kubernetes_version="${1:-1.35.0}"
crd_schemas='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

for env in dev staging prod; do
    echo "== $env"
    helm lint --strict "$chart" --values "$chart/values-$env.yaml" --kube-version "$kubernetes_version"
    helm template payment-gateway "$chart" --namespace "payment-gateway-$env" --values "$chart/values-$env.yaml" \
        --kube-version "$kubernetes_version" \
        | kubeconform -strict -summary -kubernetes-version "$kubernetes_version" \
            -schema-location default -schema-location "$crd_schemas"
done

echo "== dev with a commit's images from the registry, as the pipeline's e2e job deploys it"
helm template payment-gateway "$chart" --namespace payment-gateway-dev --values "$chart/values-dev.yaml" \
    --set image.registry=registry.gitlab.com/lecorrr/payment-gateway \
    --set image.tag=0123456789abcdef0123456789abcdef01234567 --kube-version "$kubernetes_version" \
    | kubeconform -strict -summary -kubernetes-version "$kubernetes_version" -schema-location default

# Fails unless helm template refuses the values given after EXPECTED with an error that contains EXPECTED.
refuse() {
    local expected="$1" output
    shift
    if output="$(helm template payment-gateway "$chart" "$@" 2>&1)"; then
        echo "FAIL helm template took $*" >&2
        return 1
    fi
    if ! grep -qF -- "$expected" <<< "$output"; then
        echo "FAIL helm template refused $*, but not with \"$expected\":" >&2
        echo "$output" >&2
        return 1
    fi
    echo "refused: $*"
}

echo "== values the chart refuses"
refuse "additional properties 'replcas' not allowed" --set gateway.replcas=2
refuse "riskWhenUnavailable" --set gateway.riskWhenUnavailable=maybe
refuse "/gateway/providers" --set gateway.providers=stripe
refuse "database.url is needed" --set postgres.enabled=false
refuse "redis.host is needed" --set redis.enabled=false
refuse "could never be drained" --set gateway.podDisruptionBudget.enabled=true
refuse "minReplicas is above maxReplicas" --set gateway.autoscaling.enabled=true \
    --set gateway.autoscaling.minReplicas=5
echo "The chart checks out for every environment"
