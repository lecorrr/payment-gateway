#!/usr/bin/env bash
# Puts the pipeline's tools into /usr/local/bin of a job's container, each in the version below and checked before it
# is installed: against the SHA-256 written here, copied from the checksum file of the tool's release, or, for Helm,
# kubectl and Terraform, which come from their own download sites, against the checksum the site publishes beside
# the file. Needs curl, tar, unzip and sha256sum; it is for the pipeline's containers, which run as root.
#
#   bash ci/install-tools.sh helm kubeconform kubectl k3d trivy gitleaks terraform tflint
set -euo pipefail

HELM_VERSION=4.3.2
# The k3d cluster runs Kubernetes 1.35 (deploy/k3d/cluster.yaml); kubectl works within one minor version of it.
KUBECTL_VERSION=1.35.11
KUBECONFORM_VERSION=0.8.0
KUBECONFORM_SHA256=9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883
K3D_VERSION=5.9.0
K3D_SHA256=06d8f25bc3a971c4eb29e0ff08429b180402db0f4dec838c9eac427e296800a0
TRIVY_VERSION=0.76.2
TRIVY_SHA256=bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf
GITLEAKS_VERSION=8.30.1
GITLEAKS_SHA256=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
TERRAFORM_VERSION=1.17.2
TFLINT_VERSION=0.66.0
TFLINT_SHA256=22be11cadc25cd02401e94c0ccc91aa63467debb8485c2a755d98f4c0a9d1e2e
# The version deploy/aws/.tflint.hcl asks for.
TFLINT_AWS_VERSION=0.50.0
TFLINT_AWS_SHA256=cdd433fdbc0929e7b9f9709dd9e72237fdfe1fc235674445c284b1e4b94a5c26

bin=/usr/local/bin

fail() {
    echo "install-tools: $*" >&2
    exit 1
}

(( $# > 0 )) || fail "usage: bash ci/install-tools.sh TOOL..., of helm kubeconform kubectl k3d trivy gitleaks terraform tflint"
[[ -w "$bin" ]] || fail "$bin is not writable: this script is for the pipeline's containers, which run as root"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Downloads URL as NAME in the work folder, trying again when the connection breaks off.
download() {
    curl --fail --silent --show-error --location --retry 3 --retry-all-errors --output "$work/$2" "$1" \
        || fail "could not download $1"
}

# Fails unless NAME in the work folder has the SHA-256 given.
check_sha256() {
    local expected="$1" name="$2" actual
    actual="$(sha256sum "$work/$name" | cut -d ' ' -f 1)"
    [[ "$actual" == "$expected" ]] || fail "$name has the SHA-256 $actual, not the published $expected"
}

install_helm() {
    local archive="helm-v$HELM_VERSION-linux-amd64.tar.gz"
    download "https://get.helm.sh/$archive" "$archive"
    download "https://get.helm.sh/$archive.sha256sum" "$archive.sha256sum"
    check_sha256 "$(cut -d ' ' -f 1 "$work/$archive.sha256sum")" "$archive"
    tar -xzf "$work/$archive" -C "$work" linux-amd64/helm
    install -m 0755 "$work/linux-amd64/helm" "$bin/helm"
    helm version --short
}

install_kubectl() {
    local url="https://dl.k8s.io/release/v$KUBECTL_VERSION/bin/linux/amd64/kubectl"
    download "$url" kubectl
    download "$url.sha256" kubectl.sha256
    check_sha256 "$(cut -d ' ' -f 1 "$work/kubectl.sha256")" kubectl
    install -m 0755 "$work/kubectl" "$bin/kubectl"
    kubectl version --client | sed -n 1p
}

install_kubeconform() {
    download "https://github.com/yannh/kubeconform/releases/download/v$KUBECONFORM_VERSION/kubeconform-linux-amd64.tar.gz" \
        kubeconform.tar.gz
    check_sha256 "$KUBECONFORM_SHA256" kubeconform.tar.gz
    tar -xzf "$work/kubeconform.tar.gz" -C "$work" kubeconform
    install -m 0755 "$work/kubeconform" "$bin/kubeconform"
    echo "kubeconform $(kubeconform -v)"
}

install_k3d() {
    download "https://github.com/k3d-io/k3d/releases/download/v$K3D_VERSION/k3d-linux-amd64" k3d
    check_sha256 "$K3D_SHA256" k3d
    install -m 0755 "$work/k3d" "$bin/k3d"
    k3d version | sed -n 1p
}

install_trivy() {
    local archive="trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz"
    download "https://github.com/aquasecurity/trivy/releases/download/v$TRIVY_VERSION/$archive" "$archive"
    check_sha256 "$TRIVY_SHA256" "$archive"
    tar -xzf "$work/$archive" -C "$work" trivy
    install -m 0755 "$work/trivy" "$bin/trivy"
    trivy --version | sed -n 1p
}

install_gitleaks() {
    local archive="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
    download "https://github.com/gitleaks/gitleaks/releases/download/v$GITLEAKS_VERSION/$archive" "$archive"
    check_sha256 "$GITLEAKS_SHA256" "$archive"
    tar -xzf "$work/$archive" -C "$work" gitleaks
    install -m 0755 "$work/gitleaks" "$bin/gitleaks"
    echo "gitleaks $(gitleaks version)"
}

install_terraform() {
    local archive="terraform_${TERRAFORM_VERSION}_linux_amd64.zip"
    local base="https://releases.hashicorp.com/terraform/$TERRAFORM_VERSION"
    download "$base/$archive" "$archive"
    download "$base/terraform_${TERRAFORM_VERSION}_SHA256SUMS" terraform.sha256sums
    check_sha256 "$(awk -v name="$archive" '$2 == name { print $1 }' "$work/terraform.sha256sums")" "$archive"
    unzip -o -q "$work/$archive" terraform -d "$work"
    install -m 0755 "$work/terraform" "$bin/terraform"
    terraform version | sed -n 1p
}

install_tflint() {
    download "https://github.com/terraform-linters/tflint/releases/download/v$TFLINT_VERSION/tflint_linux_amd64.zip" \
        tflint.zip
    check_sha256 "$TFLINT_SHA256" tflint.zip
    unzip -o -q "$work/tflint.zip" tflint -d "$work"
    install -m 0755 "$work/tflint" "$bin/tflint"
    # The AWS ruleset goes where tflint looks for the plugin deploy/aws/.tflint.hcl names, so that no tflint --init
    # is needed: that asks GitHub's API, whose limit for requests without a token shared runners run into.
    local plugins="${TFLINT_PLUGIN_DIR:-$HOME/.tflint.d/plugins}"
    plugins="$plugins/github.com/terraform-linters/tflint-ruleset-aws/$TFLINT_AWS_VERSION"
    download "https://github.com/terraform-linters/tflint-ruleset-aws/releases/download/v$TFLINT_AWS_VERSION/tflint-ruleset-aws_linux_amd64.zip" \
        tflint-ruleset-aws.zip
    check_sha256 "$TFLINT_AWS_SHA256" tflint-ruleset-aws.zip
    mkdir -p "$plugins"
    unzip -o -q "$work/tflint-ruleset-aws.zip" tflint-ruleset-aws -d "$plugins"
    tflint --version | sed -n 1p
}

for tool in "$@"; do
    case "$tool" in
        helm | kubectl | kubeconform | k3d | trivy | gitleaks | terraform | tflint) "install_$tool" ;;
        *) fail "no tool $tool here; there are helm kubeconform kubectl k3d trivy gitleaks terraform tflint" ;;
    esac
done
