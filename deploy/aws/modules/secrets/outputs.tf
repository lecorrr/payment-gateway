output "app_secret_name" {
  description = "The name External Secrets reads the app's keys by."
  value       = aws_secretsmanager_secret.app.name
}

output "external_secrets_role_arn" {
  description = "The IAM role External Secrets' controller gets through Pod Identity."
  value       = aws_iam_role.external_secrets.arn
}
