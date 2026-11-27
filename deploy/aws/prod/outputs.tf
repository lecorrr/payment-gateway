output "cluster_name" {
  description = "The EKS cluster, for aws eks update-kubeconfig."
  value       = module.eks.cluster_name
}

output "chart_values" {
  description = "What values-prod.yaml takes from here after an apply: the stores' addresses and the secrets' names."
  value = {
    database_url            = module.gateway_db.jdbc_url
    ledger_database_url     = module.ledger_db.jdbc_url
    redis_host              = module.redis.host
    kafka_bootstrap_servers = module.kafka.bootstrap_brokers_tls
    secrets = {
      database        = module.gateway_db.secret_name
      ledger_database = module.ledger_db.secret_name
      app             = module.secrets.app_secret_name
    }
  }
}

output "store_cidrs" {
  description = "Each store's range in the VPC, which the chart's NetworkPolicies tell the stores apart by."
  value       = local.store_cidrs
}

output "store_egress" {
  description = "The NetworkPolicy egress rules each app needs to its stores, as values-prod.yaml's networkPolicy has them."
  value = {
    gatewayEgress     = [local.store_egress_rules.gateway_db, local.store_egress_rules.redis, local.store_egress_rules.kafka]
    riskServiceEgress = [local.store_egress_rules.redis]
    ledgerEgress      = [local.store_egress_rules.ledger_db, local.store_egress_rules.kafka]
  }
}
