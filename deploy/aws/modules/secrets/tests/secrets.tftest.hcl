# Applies the module to a mocked AWS provider, so it needs no AWS account and the policies' inputs are all known.
# IAM checks that a role's policies are JSON and EKS that a role is an ARN, so the mocked ones are.
mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/payment-test-prod-external-secrets"
    }
  }
}

variables {
  name         = "payment-test/prod"
  cluster_name = "payment-test"
  database_secret_arns = [
    "arn:aws:secretsmanager:eu-central-1:123456789012:secret:payment-test/prod/gateway-db-AbCdEf",
    "arn:aws:secretsmanager:eu-central-1:123456789012:secret:payment-test/prod/ledger-db-GhIjKl",
  ]
}

run "lets_external_secrets_read_these_secrets_and_no_others" {
  assert {
    condition = (toset(one(data.aws_iam_policy_document.read_secrets.statement).resources)
    == toset(concat([aws_secretsmanager_secret.app.arn], var.database_secret_arns)))
    error_message = "External Secrets may read the app's secret and the databases' secrets only."
  }
  assert {
    condition     = alltrue([for action in one(data.aws_iam_policy_document.read_secrets.statement).actions : startswith(action, "secretsmanager:") && !strcontains(action, "*")])
    error_message = "External Secrets may only read secrets, action by action, with no wildcard."
  }
  assert {
    condition     = alltrue([for action in one(data.aws_iam_policy_document.read_secrets.statement).actions : !contains(["secretsmanager:PutSecretValue", "secretsmanager:UpdateSecret", "secretsmanager:DeleteSecret"], action)])
    error_message = "External Secrets may not change or delete secrets."
  }
}

run "gives_the_role_to_external_secrets_through_pod_identity" {
  assert {
    condition     = one(one(data.aws_iam_policy_document.pod_identity_trust.statement).principals).identifiers == toset(["pods.eks.amazonaws.com"])
    error_message = "Only EKS Pod Identity may hand out the role."
  }
  assert {
    condition = (aws_eks_pod_identity_association.external_secrets.namespace == "external-secrets"
      && aws_eks_pod_identity_association.external_secrets.service_account == "external-secrets"
    && aws_eks_pod_identity_association.external_secrets.role_arn == aws_iam_role.external_secrets.arn)
    error_message = "The role must go to External Secrets' controller."
  }
}

run "names_the_app_secret_after_the_prefix" {
  assert {
    condition     = output.app_secret_name == "payment-test/prod/app"
    error_message = "The app's secret must be <name>/app, the name values-prod.yaml's externalSecret reads."
  }
}
