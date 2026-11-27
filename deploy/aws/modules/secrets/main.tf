# The app's Secret comes from AWS Secrets Manager: External Secrets reads the secrets below with an IAM role it gets
# through EKS Pod Identity, and writes the Secret the chart's existingSecret names (templates/external-secret.yaml).

# The app's keys other than the databases' passwords, as JSON: GATEWAY_FINGERPRINT_KEY, GATEWAY_MONOBANK_TOKEN and,
# for the demo shop, GATEWAY_DEMO_MERCHANT_API_KEY. Their values are put in by hand, never through Terraform, so the
# state doesn't hold them.
resource "aws_secretsmanager_secret" "app" {
  name        = "${var.name}/app"
  description = "The app's keys besides the databases' passwords, which External Secrets puts into the app's Secret"
}

data "aws_iam_policy_document" "pod_identity_trust" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

# Reading these secrets and no others; the actions are those External Secrets' AWS provider uses.
data "aws_iam_policy_document" "read_secrets" {
  statement {
    actions = [
      "secretsmanager:GetResourcePolicy",
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:ListSecretVersionIds",
    ]
    resources = concat([aws_secretsmanager_secret.app.arn], var.database_secret_arns)
  }
}

resource "aws_iam_role" "external_secrets" {
  name               = "${replace(var.name, "/", "-")}-external-secrets"
  description        = "External Secrets reading the secrets of ${var.name}"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

resource "aws_iam_role_policy" "read_secrets" {
  name   = "read-secrets"
  role   = aws_iam_role.external_secrets.id
  policy = data.aws_iam_policy_document.read_secrets.json
}

resource "aws_eks_pod_identity_association" "external_secrets" {
  cluster_name    = var.cluster_name
  namespace       = var.external_secrets_namespace
  service_account = var.external_secrets_service_account
  role_arn        = aws_iam_role.external_secrets.arn
}
