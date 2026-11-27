output "jdbc_url" {
  description = "The app's DB_URL, as values-prod.yaml's database.url has it."
  value       = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${var.database_name}?sslmode=require"
}

output "secret_arn" {
  description = "The secret with the user and the password."
  value       = aws_secretsmanager_secret.this.arn
}

output "secret_name" {
  description = "The name External Secrets reads the secret by."
  value       = aws_secretsmanager_secret.this.name
}

output "security_group_id" {
  description = "The database's security group."
  value       = aws_security_group.this.id
}
