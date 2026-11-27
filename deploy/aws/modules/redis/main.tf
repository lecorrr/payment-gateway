# The Redis of the apps on ElastiCache, as Valkey: the providers' open circuits the gateways share, and each card's
# payments, which risk-service counts. A primary and a replica in another zone, TLS only, reachable from the EKS nodes
# alone.

resource "aws_elasticache_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "this" {
  name        = "${var.name}-redis"
  description = "Valkey of ${var.name}, from the EKS nodes only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "redis" {
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = var.client_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "Valkey from the EKS nodes"
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "Open circuits the gateways share, and each card's payments for risk-service"
  engine               = "valkey"
  engine_version       = var.engine_version
  node_type            = var.node_type
  port                 = 6379

  # The replica is promoted when the primary or its zone fails.
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  # TLS only: values-prod.yaml has the apps connect with SPRING_DATA_REDIS_SSL_ENABLED.
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  at_rest_encryption_enabled = true

  # Nothing in it needs a backup, as with Redis in a k3d namespace: circuits close again and card counts start over.
  snapshot_retention_limit = 0
}
