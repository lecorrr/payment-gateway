variable "name" {
  description = "The prefix of the secrets' names, such as payment-gateway/prod; the app's secret is <name>/app."
  type        = string
}

variable "cluster_name" {
  description = "The EKS cluster External Secrets runs in."
  type        = string
}

variable "database_secret_arns" {
  description = "The databases' secrets, which External Secrets may read besides the app's."
  type        = list(string)
}

variable "external_secrets_namespace" {
  description = "The namespace of External Secrets' controller."
  type        = string
  default     = "external-secrets"
}

variable "external_secrets_service_account" {
  description = "The service account of External Secrets' controller."
  type        = string
  default     = "external-secrets"
}
