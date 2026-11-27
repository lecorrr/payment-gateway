#!/usr/bin/env bash
# The pipeline's release job: gives the commit's five images, which passed every job before this one, the version
# they were built as too: $CI_REGISTRY_IMAGE/<app>:$CI_COMMIT_SHA gets the tag $APP_VERSION (such as 0.10.0) from the
# images job. The images are only tagged again, not built again, so the version is exactly what the checks saw. A
# version names one build: an image that has the version's tag already keeps it.
#
#   bash ci/release-images.sh
set -euo pipefail

apps=(gateway-service risk-service ledger-service acquirer-sim merchant-sim)
registry="${CI_REGISTRY_IMAGE:?GitLab sets CI_REGISTRY_IMAGE; this script is the pipeline release job}"
commit="${CI_COMMIT_SHA:?GitLab sets CI_COMMIT_SHA; this script is the pipeline release job}"
version="${APP_VERSION:?APP_VERSION comes from the images job, through its images.env}"

fail() {
    echo "release-images: $*" >&2
    exit 1
}

# The digest of the image at REFERENCE in the registry; nothing when the registry has no such tag.
digest_of() {
    local output
    if output="$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' "$1" 2>&1)"; then
        echo "$output"
    elif [[ "$output" == *"not found"* ]]; then
        echo ""
    else
        fail "could not look up $1: $output"
    fi
}

printf '%s' "${CI_REGISTRY_PASSWORD:?}" | docker login --username "${CI_REGISTRY_USER:?}" --password-stdin \
    "${CI_REGISTRY:?}"

for app in "${apps[@]}"; do
    built="$(digest_of "$registry/$app:$commit")"
    [[ -n "$built" ]] || fail "the registry has no $registry/$app:$commit; the images job pushes it"
    released="$(digest_of "$registry/$app:$version")"
    if [[ -z "$released" ]]; then
        # A copy of the manifest under the new tag, so both tags have the same digest; by default buildx would wrap
        # a single image in an index of its own.
        docker buildx imagetools create --prefer-index=false --tag "$registry/$app:$version" "$registry/$app@$built"
        echo "released $app $version: $built"
    elif [[ "$released" == "$built" ]]; then
        echo "$app $version is this commit's image already: $built"
    else
        echo "$app $version is released already, as $released; this commit's image keeps only its commit tag." \
            "A new release needs a new version in pom.xml."
    fi
done
