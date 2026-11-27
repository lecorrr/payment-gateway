# Plans prod with every provider but random mocked, so it needs no AWS account. The modules check that ARNs and
# policies look like ARNs and policies, so the mocked ones do.
mock_provider "aws" {
  mock_data "aws_partition" {
    defaults = {
      partition  = "aws"
      dns_suffix = "amazonaws.com"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/terraform"
    }
  }
  mock_data "aws_iam_session_context" {
    defaults = {
      issuer_arn = "arn:aws:iam::123456789012:user/terraform"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/payment-gateway-mock"
    }
  }
  mock_resource "aws_iam_policy" {
    defaults = {
      arn = "arn:aws:iam::123456789012:policy/payment-gateway-mock"
    }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:eu-central-1:123456789012:log-group:payment-gateway-mock"
    }
  }
  mock_resource "aws_eks_cluster" {
    defaults = {
      arn                   = "arn:aws:eks:eu-central-1:123456789012:cluster/payment-gateway"
      endpoint              = "https://0123456789ABCDEF0123456789ABCDEF.gr7.eu-central-1.eks.amazonaws.com"
      certificate_authority = [{ data = "bW9jaw==" }]
      identity              = [{ oidc = [{ issuer = "https://oidc.eks.eu-central-1.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF" }] }]
    }
  }
  mock_resource "aws_launch_template" {
    defaults = {
      id = "lt-0123456789abcdef0"
    }
  }
  mock_resource "aws_kms_key" {
    defaults = {
      arn = "arn:aws:kms:eu-central-1:123456789012:key/00000000-0000-0000-0000-000000000000"
    }
  }
  mock_resource "aws_msk_configuration" {
    defaults = {
      arn = "arn:aws:kafka:eu-central-1:123456789012:configuration/payment-gateway/00000000-0000-0000-0000-000000000000-1"
    }
  }
}
mock_provider "tls" {}
mock_provider "time" {}
mock_provider "cloudinit" {}
mock_provider "null" {}

run "values_prod_lets_each_app_reach_exactly_its_stores" {
  command = plan

  assert {
    condition = alltrue([
      for app, rules in output.store_egress : jsonencode([
        for rule in yamldecode(file("../../helm/payment-gateway/values-prod.yaml")).networkPolicy[app] : rule
        if contains([5432, 6379, 9094], try(rule.ports[0].port, 0))
      ]) == jsonencode(rules)
    ])
    error_message = "values-prod.yaml's networkPolicy must let each app reach exactly its stores' ranges and ports, as the output store_egress has them."
  }
}

run "gives_every_store_a_subnet_of_its_range_in_each_zone" {
  command = plan

  assert {
    condition     = length(module.vpc.intra_subnet_objects) == length(output.store_cidrs) * length(module.vpc.azs)
    error_message = "The VPC's intra subnets must be the stores' and no others."
  }
  assert {
    condition     = length(toset(module.vpc.intra_subnets_cidr_blocks)) == length(module.vpc.intra_subnets_cidr_blocks)
    error_message = "No two of the stores' subnets may have the same range."
  }
  # A subnet is in a store's /22 when it is one of the four /24s of that /22.
  assert {
    condition = alltrue([
      for store, store_cidr in output.store_cidrs : toset([
        for subnet in module.vpc.intra_subnet_objects : subnet.availability_zone
        if contains([for quarter in range(4) : cidrsubnet(store_cidr, 2, quarter)], subnet.cidr_block)
      ]) == toset(module.vpc.azs)
    ])
    error_message = "Every store must have a subnet of its own range in each zone."
  }
}
