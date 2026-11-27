variable "name" {
  description = "The replication group's ID, which its subnet group and security group are named after."
  type        = string
}

variable "vpc_id" {
  description = "The VPC of the cache."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets of the cache's own, one in each zone, so that the chart's NetworkPolicies can tell it from the other stores."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "A primary and a replica in different zones need subnets in at least two zones."
  }
}

variable "client_security_group_id" {
  description = "The security group that may connect: the EKS nodes', whose pods the chart's NetworkPolicies then sort out."
  type        = string
}

variable "engine_version" {
  description = "The Valkey version; Valkey speaks the protocol of the Redis the apps use in compose and k3d."
  type        = string
  default     = "9.1"
}

variable "node_type" {
  description = "The node type of the primary and its replica."
  type        = string
  default     = "cache.m7g.large"
}
