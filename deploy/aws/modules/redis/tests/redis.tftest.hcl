# Plans the module with a mocked AWS provider, so it needs no AWS account.
mock_provider "aws" {}

variables {
  name                     = "payment-test"
  vpc_id                   = "vpc-0123456789abcdef0"
  subnet_ids               = ["subnet-0bbbbbbbbbbbbbbb1", "subnet-0bbbbbbbbbbbbbbb2", "subnet-0bbbbbbbbbbbbbbb3"]
  client_security_group_id = "sg-0123456789abcdef0"
}

run "takes_tls_only_and_keeps_a_replica_in_another_zone" {
  command = plan

  assert {
    condition = (aws_elasticache_replication_group.this.transit_encryption_enabled
      && aws_elasticache_replication_group.this.transit_encryption_mode == "required"
    && aws_elasticache_replication_group.this.at_rest_encryption_enabled)
    error_message = "The cache must take TLS only and encrypt what it stores."
  }
  assert {
    condition = (aws_elasticache_replication_group.this.num_cache_clusters == 2
      && aws_elasticache_replication_group.this.automatic_failover_enabled
    && aws_elasticache_replication_group.this.multi_az_enabled)
    error_message = "The cache must have a replica in another zone to fail over to."
  }
  assert {
    condition     = toset(aws_elasticache_subnet_group.this.subnet_ids) == toset(var.subnet_ids)
    error_message = "The cache must live in its own subnets only."
  }
}

run "lets_only_the_client_security_group_in_on_6379" {
  command = plan

  assert {
    condition = (aws_vpc_security_group_ingress_rule.redis.referenced_security_group_id == var.client_security_group_id
      && aws_vpc_security_group_ingress_rule.redis.from_port == 6379
    && aws_vpc_security_group_ingress_rule.redis.to_port == 6379)
    error_message = "Only the client security group may connect, on 6379."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.redis.cidr_ipv4 == null
    error_message = "No address range may connect, only the client security group."
  }
}

run "refuses_a_single_subnet" {
  command = plan

  variables {
    subnet_ids = ["subnet-0bbbbbbbbbbbbbbb1"]
  }

  expect_failures = [var.subnet_ids]
}
