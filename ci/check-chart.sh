#!/usr/bin/env bash
# Checks the Helm chart for every environment: helm lint, then helm template with the environment's values, and what
# it renders against the schemas of Kubernetes itself and of the custom resources it makes, such as the Prometheus
# Operator's ServiceMonitor and External Secrets' ExternalSecret (kubeconform), so that a values file or a template a
# cluster would refuse fails here; prod against the Kubernetes of its EKS cluster, with External Secrets' store for
# it. Last, values the chart must refuse, each with the error that says why, so the chart's schema and checks are
# known to work. Needs helm and kubeconform.
# Usage: bash ci/check-chart.sh [Kubernetes version of dev and staging, default 1.35.0, k3d's]
set -euo pipefail

cd "$(dirname "$0")/.."
chart=deploy/helm/payment-gateway
kubernetes_version="${1:-1.35.0}"
crd_schemas='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

# Prod's Kubernetes is the one deploy/aws/prod/cluster.tf gives its EKS cluster.
eks_minor="$(sed -n 's/^ *kubernetes_version *= *"\([0-9]*\.[0-9]*\)"$/\1/p' deploy/aws/prod/cluster.tf)"
[[ -n "$eks_minor" ]] || { echo "FAIL no kubernetes_version in deploy/aws/prod/cluster.tf" >&2; exit 1; }
eks_kubernetes_version="$eks_minor.0"

for env in dev staging prod; do
    version="$kubernetes_version"
    [[ "$env" == prod ]] && version="$eks_kubernetes_version"
    echo "== $env, on Kubernetes $version"
    helm lint --strict "$chart" --values "$chart/values-$env.yaml" --kube-version "$version"
    helm template payment-gateway "$chart" --namespace "payment-gateway-$env" --values "$chart/values-$env.yaml" \
        --kube-version "$version" \
        | kubeconform -strict -summary -kubernetes-version "$version" \
            -schema-location default -schema-location "$crd_schemas"
done

echo "== External Secrets' store for prod (deploy/aws/cluster-secret-store.yaml)"
kubeconform -strict -summary -kubernetes-version "$eks_kubernetes_version" -schema-location default \
    -schema-location "$crd_schemas" deploy/aws/cluster-secret-store.yaml

echo "== dev with a commit's images from the registry, as the pipeline's e2e job deploys it"
helm template payment-gateway "$chart" --namespace payment-gateway-dev --values "$chart/values-dev.yaml" \
    --set image.registry=registry.gitlab.com/lecorrr/payment-gateway \
    --set image.tag=0123456789abcdef0123456789abcdef01234567 --kube-version "$kubernetes_version" \
    | kubeconform -strict -summary -kubernetes-version "$kubernetes_version" -schema-location default

echo "== dev without the ledger, whose objects, policies and secret keys then all go"
rendered="$(helm template payment-gateway "$chart" --namespace payment-gateway-dev --values "$chart/values-dev.yaml" \
    --set ledger.enabled=false --kube-version "$kubernetes_version")"
kubeconform -strict -summary -kubernetes-version "$kubernetes_version" -schema-location default <<< "$rendered"
if grep -nE "name: ledger|LEDGER_" <<< "$rendered"; then
    echo "FAIL ledger.enabled=false still renders the ledger's objects (above)" >&2
    exit 1
fi

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
refuse "kafka.bootstrapServers is needed" --set kafka.enabled=false
refuse "kafka.replicationFactor must be 1" --set kafka.replicationFactor=3
refuse "kafka.securityProtocol must be PLAINTEXT" --set kafka.securityProtocol=SSL
refuse "/kafka/clusterId" --set kafka.clusterId=not-an-id
refuse "ledger.database.url is needed" --set ledger.postgres.enabled=false
refuse "externalSecret.storeName is needed" --set externalSecret.enabled=true
refuse "/externalSecret/refreshInterval" --set externalSecret.refreshInterval=1d
refuse "ledger.podDisruptionBudget.minAvailable" --set ledger.podDisruptionBudget.enabled=true
refuse "could never be drained" --set gateway.podDisruptionBudget.enabled=true
refuse "minReplicas is above maxReplicas" --set gateway.autoscaling.enabled=true \
    --set gateway.autoscaling.minReplicas=5
echo "The chart checks out for every environment"
