variable "name" {
  description = "The DB instance's identifier, which its subnet group, parameter group and security group are named after."
  type        = string
}

variable "database_name" {
  description = "The database the app connects to."
  type        = string
}

variable "username" {
  description = "The master user, which the app connects as."
  type        = string
}

variable "secret_name" {
  description = "The Secrets Manager secret that gets the user and the password, as JSON with username and password."
  type        = string
}

variable "password_version" {
  description = "Raise it for a new password: Terraform sends a write-only argument only when its version changes."
  type        = number
  default     = 1
}

variable "vpc_id" {
  description = "The VPC of the database."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets of the database's own, one in each zone, so that the chart's NetworkPolicies can tell it from the other stores."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "A Multi-AZ database needs subnets in at least two zones."
  }
}

variable "client_security_group_id" {
  description = "The security group that may connect: the EKS nodes', whose pods the chart's NetworkPolicies then sort out."
  type        = string
}

variable "instance_class" {
  description = "The DB instance class."
  type        = string
  default     = "db.m7g.large"
}

variable "allocated_storage_gb" {
  description = "The storage the database starts with."
  type        = number
  default     = 100
}

variable "max_allocated_storage_gb" {
  description = "How far RDS may grow the storage by itself."
  type        = number
  default     = 500
}

variable "backup_retention_days" {
  description = "How long RDS keeps the daily backups, which a restore to any second within them needs."
  type        = number
  default     = 14
}
