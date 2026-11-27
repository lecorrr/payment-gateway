output "host" {
  description = "The primary's address, as values-prod.yaml's redis.host has it."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  description = "The port the apps connect to."
  value       = aws_elasticache_replication_group.this.port
}

output "security_group_id" {
  description = "The cache's security group."
  value       = aws_security_group.this.id
}
