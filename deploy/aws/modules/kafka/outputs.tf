output "bootstrap_brokers_tls" {
  description = "The brokers' TLS listeners, as values-prod.yaml's kafka.bootstrapServers has them."
  value       = aws_msk_cluster.this.bootstrap_brokers_tls
}

output "security_group_id" {
  description = "The brokers' security group."
  value       = aws_security_group.this.id
}
