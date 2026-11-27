#!/usr/bin/env bash
# Checks the AWS Terraform of deploy/aws, which is never applied, without an AWS account: the formatting; then for
# each module and for prod, init without a backend, validate, and terraform test, whose mocked providers plan with
# made-up AWS answers and prod's test compares the chart's values-prod.yaml with it; then tflint with the AWS rules,
# and trivy's misconfiguration checks, which fail on a HIGH or CRITICAL finding in this repository's Terraform and
# manifests (what terraform init downloads, the registry's modules with their examples, is their authors' to check).
# Needs terraform, tflint with the AWS ruleset, and trivy (ci/install-tools.sh terraform tflint trivy).
# Usage: bash ci/check-terraform.sh
set -euo pipefail

cd "$(dirname "$0")/.."
aws=deploy/aws

# Each provider is downloaded once for all the inits. No lock file is kept (versions.tf pins prod's providers), so
# the cache can't put one out of step.
TF_PLUGIN_CACHE_DIR="$(mktemp -d)"
export TF_PLUGIN_CACHE_DIR TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE=true TF_IN_AUTOMATION=true
trap 'rm -rf "$TF_PLUGIN_CACHE_DIR"' EXIT

echo "== terraform fmt"
terraform fmt -check -recursive -diff "$aws"

for dir in "$aws"/modules/* "$aws/prod"; do
    echo "== $dir"
    if ! output="$(terraform -chdir="$dir" init -backend=false -input=false -no-color 2>&1)"; then
        echo "$output" >&2
        exit 1
    fi
    terraform -chdir="$dir" validate -no-color
    terraform -chdir="$dir" test -no-color
done

echo "== tflint"
tflint --chdir="$aws" --recursive --config="$PWD/$aws/.tflint.hcl" --format=compact

echo "== trivy"
# Not the .terraform folders: the eks module comes with example manifests that are no part of prod, and that trivy
# would scan as this repository's.
trivy config --exit-code 1 --severity HIGH,CRITICAL --skip-dirs '**/.terraform' --tf-exclude-downloaded-modules "$aws"

echo "The Terraform checks out"
