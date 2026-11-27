variable "name" {
  description = "The MSK cluster's name, which its configuration, log group and security group are named after."
  type        = string
}

variable "kafka_version" {
  description = "The Kafka version, as MSK names it; the apache/kafka:4.2.2 of compose and k3d is a 4.2."
  type        = string
  default     = "4.2.x"
}

variable "vpc_id" {
  description = "The VPC of the brokers."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets of the brokers' own, one broker in each, so that the chart's NetworkPolicies can tell Kafka from the other stores."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 3
    error_message = "Three replicas of each partition, two of them in sync, need brokers in at least three zones."
  }
}

variable "client_security_group_id" {
  description = "The security group that may connect: the EKS nodes', whose pods the chart's NetworkPolicies then sort out."
  type        = string
}

variable "instance_type" {
  description = "The brokers' instance type."
  type        = string
  default     = "kafka.m7g.large"
}

variable "volume_size_gb" {
  description = "Each broker's storage."
  type        = number
  default     = 100
}

variable "log_retention_days" {
  description = "How long CloudWatch keeps the brokers' logs."
  type        = number
  default     = 30
}
