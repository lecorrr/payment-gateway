# The stores the chart's in-namespace PostgreSQL, Redis and Kafka stand in for on k3d, each in subnets of its own and
# open to the EKS nodes only.

module "gateway_db" {
  source = "../modules/postgres"

  name          = local.name
  database_name = "gateway"
  username      = "gateway"
  secret_name   = "${local.name}/prod/gateway-db"

  vpc_id                   = module.vpc.vpc_id
  subnet_ids               = local.store_subnet_ids.gateway_db
  client_security_group_id = module.eks.node_security_group_id
}

module "ledger_db" {
  source = "../modules/postgres"

  name          = "payment-ledger"
  database_name = "ledger"
  username      = "ledger"
  secret_name   = "${local.name}/prod/ledger-db"

  vpc_id                   = module.vpc.vpc_id
  subnet_ids               = local.store_subnet_ids.ledger_db
  client_security_group_id = module.eks.node_security_group_id
}

module "redis" {
  source = "../modules/redis"

  name                     = local.name
  vpc_id                   = module.vpc.vpc_id
  subnet_ids               = local.store_subnet_ids.redis
  client_security_group_id = module.eks.node_security_group_id
}

module "kafka" {
  source = "../modules/kafka"

  name                     = local.name
  vpc_id                   = module.vpc.vpc_id
  subnet_ids               = local.store_subnet_ids.kafka
  client_security_group_id = module.eks.node_security_group_id
}

module "secrets" {
  source = "../modules/secrets"

  name                 = "${local.name}/prod"
  cluster_name         = module.eks.cluster_name
  database_secret_arns = [module.gateway_db.secret_arn, module.ledger_db.secret_arn]
}
