locals {
  name = "payment-gateway"
  azs  = ["${var.region}a", "${var.region}b", "${var.region}c"]

  # 10.0.0.0/16, as values-prod.yaml has it, with a /22 of its own for each store and one /24 of that in each zone.
  # The chart's NetworkPolicies let each app reach only its stores' ranges, which keeps the gateway away from the
  # ledger's database; tests/prod.tftest.hcl checks that values-prod.yaml has these ranges.
  vpc_cidr = "10.0.0.0/16"
  store_cidrs = {
    gateway_db = "10.0.200.0/22"
    ledger_db  = "10.0.204.0/22"
    redis      = "10.0.208.0/22"
    kafka      = "10.0.212.0/22"
  }
  store_ports = {
    gateway_db = 5432
    ledger_db  = 5432
    redis      = 6379
    kafka      = 9094
  }
  store_subnet_cidrs = {
    for store, cidr in local.store_cidrs : store => [for zone in range(length(local.azs)) : cidrsubnet(cidr, 2, zone)]
  }
  # A NetworkPolicy egress rule to each store, in the form of values-prod.yaml's.
  store_egress_rules = {
    for store, cidr in local.store_cidrs : store => {
      to    = [{ ipBlock = { cidr = cidr } }]
      ports = [{ port = local.store_ports[store] }]
    }
  }

  # Each store's subnet IDs, found by their CIDR blocks among the VPC's intra subnets.
  intra_subnet_ids = zipmap(module.vpc.intra_subnets_cidr_blocks, module.vpc.intra_subnets)
  store_subnet_ids = {
    for store, cidrs in local.store_subnet_cidrs : store => [for cidr in cidrs : local.intra_subnet_ids[cidr]]
  }
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.9.0"

  name = local.name
  cidr = local.vpc_cidr
  azs  = local.azs

  # The load balancer's and the NAT gateways'.
  public_subnets = ["10.0.0.0/24", "10.0.1.0/24", "10.0.2.0/24"]
  # The nodes' and their pods': every pod takes an address of its node's subnet.
  private_subnets = ["10.0.32.0/19", "10.0.64.0/19", "10.0.96.0/19"]
  # The stores', with no route out of the VPC.
  intra_subnets = flatten(values(local.store_subnet_cidrs))
  intra_subnet_names = flatten([
    for store, cidrs in local.store_subnet_cidrs : [
      for zone, cidr in cidrs : "${local.name}-${replace(store, "_", "-")}-${local.azs[zone]}"
    ]
  ])

  # The gateway's way out to monobank and the merchants: a NAT gateway in each zone, so that a zone's outage leaves
  # the other zones theirs.
  enable_nat_gateway     = true
  one_nat_gateway_per_az = true

  # Where Kubernetes puts load balancers: internet-facing ones in the public subnets, internal ones in the private.
  public_subnet_tags  = { "kubernetes.io/role/elb" = "1" }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = "1" }

  # Every connection in the VPC, kept for a year.
  enable_flow_log                                 = true
  create_flow_log_cloudwatch_log_group            = true
  create_flow_log_cloudwatch_iam_role             = true
  flow_log_max_aggregation_interval               = 60
  flow_log_cloudwatch_log_group_retention_in_days = 365
}
