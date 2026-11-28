#!/usr/bin/env bash
# The pipeline's images job: builds the five apps' images from the jars of the verify job and has Trivy look in each
# for known vulnerabilities, in its system packages and in its Java libraries. One of HIGH or CRITICAL severity that
# has a fix fails the job, before anything is pushed. With PUSH_IMAGES=true (on main) the images then go to the
# project's container registry as $CI_REGISTRY_IMAGE/<app>:$CI_COMMIT_SHA. Writes images.env with APP_VERSION, the
# version the jars were built as, for the release job.
#
#   bash ci/build-images.sh
set -euo pipefail

apps=(gateway-service risk-service ledger-service acquirer-sim merchant-sim)
registry="${CI_REGISTRY_IMAGE:?GitLab sets CI_REGISTRY_IMAGE; this script is the pipeline images job}"
tag="${CI_COMMIT_SHA:?GitLab sets CI_COMMIT_SHA; this script is the pipeline images job}"

fail() {
    echo "build-images: $*" >&2
    exit 1
}

# Spring Boot's repackaging leaves META-INF/ at the jar's root, apart from META-INF/services/, aop.xml and Kotlin
# module files, which move into BOOT-INF/classes/. So build-info.properties stays META-INF/build-info.properties.
build_info=META-INF/build-info.properties

# The build.version Spring Boot's build-info wrote into an app's jar.
jar_version() {
    local jar="$1/target/$1.jar" properties
    [[ -f "$jar" ]] || fail "$jar is missing; the job takes the jars from the verify job's artifacts"
    # This image's unzip is BusyBox's, which prints nothing and succeeds for an entry the jar doesn't have.
    properties="$(unzip -p "$jar" "$build_info")"
    [[ -n "$properties" ]] || fail "$jar has no $build_info, which spring-boot-maven-plugin's build-info goal writes"
    sed -n 's/^build\.version=//p' <<< "$properties"
}

version="$(jar_version gateway-service)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "the gateway's jar has no release version in its build-info: '$version'"
for app in "${apps[@]}"; do
    built="$(jar_version "$app")"
    [[ "$built" == "$version" ]] || fail "$app's jar is version '$built', the gateway's is $version"
done

for app in "${apps[@]}"; do
    echo "building $registry/$app:$tag, version $version"
    # One image manifest, without the provenance and SBOM attestations BuildKit may add beside it: what the
    # registry, docker save and k3d image import then carry around is the image alone.
    docker build --quiet --provenance=false --sbom=false --tag "$registry/$app:$tag" "$app" > /dev/null
done

# Exceptions, each with its reason and an expiry date, would go in .trivyignore.yaml; there are none.
trivy_args=(image --scanners vuln --severity "HIGH,CRITICAL" --ignore-unfixed --image-src docker --no-progress
    --skip-version-check --timeout 15m --exit-code 3)
if [[ -f .trivyignore.yaml ]]; then
    trivy_args+=(--ignorefile .trivyignore.yaml)
fi
vulnerable=()
for app in "${apps[@]}"; do
    status=0
    trivy "${trivy_args[@]}" "$registry/$app:$tag" || status=$?
    case "$status" in
        0) ;;
        3) vulnerable+=("$app") ;;
        *) fail "Trivy could not scan $app (exit status $status); its error is above" ;;
    esac
done
if (( ${#vulnerable[@]} > 0 )); then
    fail "Trivy found fixed HIGH or CRITICAL vulnerabilities in ${vulnerable[*]}; the tables above name them and" \
        "the versions that fix them"
fi

if [[ "${PUSH_IMAGES:-false}" == true ]]; then
    printf '%s' "${CI_REGISTRY_PASSWORD:?}" | docker login --username "${CI_REGISTRY_USER:?}" --password-stdin \
        "${CI_REGISTRY:?}"
    for app in "${apps[@]}"; do
        docker push --quiet "$registry/$app:$tag"
    done
else
    echo "not pushed: the pipeline pushes images on main only"
fi

echo "APP_VERSION=$version" > images.env
