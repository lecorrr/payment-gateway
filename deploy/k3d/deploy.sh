#!/usr/bin/env bash
# Puts one environment, dev or staging, on the local k3d cluster that create-cluster.sh made: builds the five apps'
# images from the jars of ./mvnw verify, loads them and the other images the chart uses into the cluster's nodes,
# makes the environment's namespace and Secret, and installs or upgrades the chart with the environment's values.
# It waits until every app is ready, then runs helm test. Run it again after a new build: images of other jars get
# a new tag, which replaces the pods; the same jars change nothing.
#
# With IMAGE_REGISTRY and IMAGE_TAG it deploys the five images a registry has instead, such as the ones the pipeline
# released (IMAGE_REGISTRY=registry.gitlab.com/lecorrr/payment-gateway IMAGE_TAG=0.9.3) or pushed for a commit
# (IMAGE_TAG=<the commit's full SHA>, which the pipeline's e2e job deploys). Nothing is built then, and no jars are
# needed.
#
#   deploy/k3d/deploy.sh dev|staging
set -euo pipefail

env="${1:-}"
if [[ "$env" != dev && "$env" != staging ]]; then
    echo "usage: deploy/k3d/deploy.sh dev|staging" >&2
    exit 2
fi

repo="$(cd "$(dirname "$0")/../.." && pwd)"
chart="$repo/deploy/helm/payment-gateway"
cluster=payment-gateway
# Every command names the k3d cluster's context, so whatever kubectl's current context is, nothing else is touched.
context="k3d-$cluster"
namespace="payment-gateway-$env"
release=payment-gateway
secret=payment-gateway
apps=(gateway-service risk-service ledger-service acquirer-sim merchant-sim)
# The Secret's keys (values.yaml lists what each is for), each a new random value.
secret_keys=(GATEWAY_FINGERPRINT_KEY DB_PASSWORD LEDGER_DB_PASSWORD ACQUIRER_SIM_API_KEY GATEWAY_DEMO_MERCHANT_API_KEY
    GATEWAY_DEMO_MERCHANT_WEBHOOK_SECRET)

fail() {
    echo "$*" >&2
    exit 1
}

kc() {
    kubectl --context "$context" "$@"
}

# The build.version Spring Boot's build-info wrote into a jar, or nothing.
jar_version() {
    python3 -c '
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    names = set(jar.namelist())
    for name in ("BOOT-INF/classes/META-INF/build-info.properties", "META-INF/build-info.properties"):
        if name in names:
            for line in jar.read(name).decode().splitlines():
                if line.startswith("build.version="):
                    print(line.split("=", 1)[1])
            break
' "$1"
}

# A new random value for a key of the Secret.
new_secret() {
    case "$1" in
        GATEWAY_FINGERPRINT_KEY) openssl rand -hex 32 ;;
        GATEWAY_DEMO_MERCHANT_WEBHOOK_SECRET) echo "whsec_$(openssl rand -base64 32)" ;;
        *) openssl rand -hex 16 ;;
    esac
}

# Builds the five apps' images from the jars, which must all be the version in pom.xml, and sets tag to the tag of
# this build of the jars: new jars, a new tag and so new pods; the same jars, the same tag.
build_images() {
    local version app jar built jars=()
    version="$(python3 -c '
import sys, xml.etree.ElementTree as ET
print(ET.parse(sys.argv[1]).getroot().findtext("{http://maven.apache.org/POM/4.0.0}version"))
' "$repo/pom.xml")"
    for app in "${apps[@]}"; do
        jar="$repo/$app/target/$app.jar"
        [[ -f "$jar" ]] || fail "$jar is missing: build the jars first, ./mvnw clean verify"
        built="$(jar_version "$jar")"
        [[ "$built" == "$version" ]] || fail "$jar is ${built:-of no version}, pom.xml says $version: ./mvnw clean verify"
        jars+=("$jar")
    done
    tag="$version-$(cat "${jars[@]}" | sha256sum | cut -c1-8)"
    for app in "${apps[@]}"; do
        echo "building $app:$tag"
        docker build --quiet --tag "$app:$tag" "$repo/$app" > /dev/null
    done
}

for tool in docker k3d kubectl helm openssl python3; do
    if ! command -v "$tool" > /dev/null; then
        fail "$tool is not installed; the README's Kubernetes section says where to get it"
    fi
done
k3d cluster get "$cluster" > /dev/null 2>&1 || fail "There is no k3d cluster $cluster yet: deploy/k3d/create-cluster.sh"

registry="${IMAGE_REGISTRY:-}"
tag="${IMAGE_TAG:-}"
if [[ -n "$registry" || -n "$tag" ]]; then
    [[ -n "$registry" && -n "$tag" ]] || fail "IMAGE_REGISTRY and IMAGE_TAG go together: the registry, and its images' tag"
    echo "deploying the images of $registry, tag $tag"
else
    build_images
fi

# Every image the environment uses, from the chart itself: the five apps', PostgreSQL's, Redis' and Kafka's.
values=(--values "$chart/values-$env.yaml" --set "image.tag=$tag")
if [[ -n "$registry" ]]; then
    values+=(--set "image.registry=$registry")
fi
mapfile -t images < <(helm template "$release" "$chart" --namespace "$namespace" "${values[@]}" \
    | sed -n 's/^ *image: *"\{0,1\}\([^" ]*\)"\{0,1\} *$/\1/p' | sort -u)
(( ${#images[@]} > 0 )) || fail "helm template of $chart names no images"
for image in "${images[@]}"; do
    if ! docker image inspect "$image" > /dev/null 2>&1; then
        echo "pulling $image"
        docker pull --quiet "$image" > /dev/null
    fi
done
# The nodes take the images from here, so nothing is pulled from a registry and :IfNotPresent finds them. The direct
# mode streams each image from Docker into the nodes and fails if a node refuses one; for a Docker on another machine,
# as in the pipeline, k3d would otherwise pick a mode that only logs that.
echo "loading ${images[*]} into the nodes"
k3d image import --mode direct --cluster "$cluster" "${images[@]}"

kc create namespace "$namespace" --dry-run=client --output yaml | kc apply --filename - > /dev/null
# Pods that break the restricted Pod Security Standard are refused here too.
kc label namespace "$namespace" --overwrite \
    pod-security.kubernetes.io/enforce=restricted pod-security.kubernetes.io/enforce-version=latest > /dev/null

# The environment's own random keys, made once: later deploys keep them, as the databases keep their passwords. A
# Secret an earlier version made gets the keys it lacks, such as the ledger's database password. The values go
# through files, never on a command line.
if ! kc --namespace "$namespace" get secret "$secret" > /dev/null 2>&1; then
    kc --namespace "$namespace" create secret generic "$secret" --from-env-file=<(
        for key in "${secret_keys[@]}"; do
            echo "$key=$(new_secret "$key")"
        done
    ) > /dev/null
    echo "made the Secret $secret in $namespace, with new random keys"
else
    # shellcheck disable=SC2016 # $key is the Go template's, not the shell's
    has="$(kc --namespace "$namespace" get secret "$secret" -o go-template='{{range $key, $_ := .data}}{{$key}} {{end}}')"
    for key in "${secret_keys[@]}"; do
        if [[ " $has " != *" $key "* ]]; then
            kc --namespace "$namespace" patch secret "$secret" --type merge \
                --patch-file <(printf '{"stringData": {"%s": "%s"}}' "$key" "$(new_secret "$key")") > /dev/null
            echo "added $key to the Secret $secret in $namespace"
        fi
    done
fi

helm --kube-context "$context" upgrade --install "$release" "$chart" --namespace "$namespace" "${values[@]}" \
    --wait --timeout 10m
helm --kube-context "$context" test "$release" --namespace "$namespace" --logs

echo "deploy/k3d/smoke-test.sh $env checks the environment end to end"
