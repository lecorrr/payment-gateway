# Plans the module with a mocked AWS provider, so it needs no AWS account. The cluster checks that its configuration
# and its key are ARNs, so the mocked ones are.
mock_provider "aws" {
  mock_resource "aws_msk_configuration" {
    defaults = {
      arn = "arn:aws:kafka:eu-central-1:123456789012:configuration/payment-test/00000000-0000-0000-0000-000000000000-1"
    }
  }
  mock_resource "aws_kms_key" {
    defaults = {
      arn = "arn:aws:kms:eu-central-1:123456789012:key/00000000-0000-0000-0000-000000000000"
    }
  }
}

variables {
  name                     = "payment-test"
  vpc_id                   = "vpc-0123456789abcdef0"
  subnet_ids               = ["subnet-0ccccccccccccccc1", "subnet-0ccccccccccccccc2", "subnet-0ccccccccccccccc3"]
  client_security_group_id = "sg-0123456789abcdef0"
}

run "runs_a_broker_in_each_zone_with_tls_everywhere" {
  command = plan

  assert {
    condition     = aws_msk_cluster.this.number_of_broker_nodes == length(var.subnet_ids)
    error_message = "There must be one broker in each of the subnets."
  }
  assert {
    condition = (aws_msk_cluster.this.encryption_info[0].encryption_in_transit[0].client_broker == "TLS"
    && aws_msk_cluster.this.encryption_info[0].encryption_in_transit[0].in_cluster)
    error_message = "The apps and the brokers, and the brokers among themselves, must talk TLS only."
  }
  assert {
    condition     = aws_msk_cluster.this.logging_info[0].broker_logs[0].cloudwatch_logs[0].enabled
    error_message = "The brokers' logs must go to CloudWatch."
  }
  assert {
    condition     = aws_kms_key.storage.enable_key_rotation
    error_message = "The key of the brokers' storage must be rotated."
  }
}

run "keeps_every_event_on_two_replicas_before_it_counts" {
  command = plan

  assert {
    condition     = strcontains(aws_msk_configuration.this.server_properties, "min.insync.replicas=2")
    error_message = "Two replicas must have an event before a producer that waits for all hears it is written."
  }
  assert {
    condition     = strcontains(aws_msk_configuration.this.server_properties, "unclean.leader.election.enable=false")
    error_message = "A replica that fell behind must not become the leader."
  }
}

run "lets_only_the_client_security_group_in_on_9094" {
  command = plan

  assert {
    condition = (aws_vpc_security_group_ingress_rule.kafka_tls.referenced_security_group_id == var.client_security_group_id
      && aws_vpc_security_group_ingress_rule.kafka_tls.from_port == 9094
    && aws_vpc_security_group_ingress_rule.kafka_tls.to_port == 9094)
    error_message = "Only the client security group may connect, on the TLS listener's 9094."
  }
}

run "refuses_two_zones" {
  command = plan

  variables {
    subnet_ids = ["subnet-0ccccccccccccccc1", "subnet-0ccccccccccccccc2"]
  }

  expect_failures = [var.subnet_ids]
}
