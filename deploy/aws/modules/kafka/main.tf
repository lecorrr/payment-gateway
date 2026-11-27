# The Kafka of the payment-events topic on MSK: a broker in each zone, TLS between the apps and the brokers and among
# the brokers, reachable from the EKS nodes alone.

resource "aws_security_group" "this" {
  name        = "${var.name}-kafka"
  description = "Kafka of ${var.name}, from the EKS nodes only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "kafka_tls" {
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = var.client_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 9094
  to_port                      = 9094
  description                  = "Kafka's TLS listener from the EKS nodes"
}

# The apps make their topics themselves (the gateway payment-events, the ledger its dead letters) with three replicas,
# values-prod.yaml's kafka.replicationFactor, so no other topic appears by a typo. An event counts as written once two
# replicas have it: the gateway's producer has acks=all, which waits for min.insync.replicas, and a replica that fell
# behind can't become the leader and lose events.
resource "aws_msk_configuration" "this" {
  name           = var.name
  kafka_versions = [var.kafka_version]

  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    unclean.leader.election.enable=false
  EOT
}

# The brokers' storage is encrypted with a key of the cluster's own, rotated every year, rather than MSK's default key,
# which AWS manages and nobody here can audit or revoke.
resource "aws_kms_key" "storage" {
  description             = "Storage of the MSK cluster ${var.name}"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "storage" {
  name          = "alias/${var.name}-kafka"
  target_key_id = aws_kms_key.storage.key_id
}

resource "aws_cloudwatch_log_group" "brokers" {
  name              = "/aws/msk/${var.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_msk_cluster" "this" {
  cluster_name           = var.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = length(var.subnet_ids)

  broker_node_group_info {
    instance_type   = var.instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.this.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.volume_size_gb
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.storage.arn

    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  # TLS without client certificates or passwords, as values-prod.yaml has the apps connect: the security group lets
  # only the EKS nodes in, and the chart's NetworkPolicies only the gateway's and the ledger's pods.
  client_authentication {
    unauthenticated = true
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.brokers.name
      }
    }
  }
}
