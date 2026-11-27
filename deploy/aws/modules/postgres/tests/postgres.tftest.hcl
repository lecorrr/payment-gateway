# Plans the module with a mocked AWS provider, so it needs no AWS account.
mock_provider "aws" {}

variables {
  name                     = "payment-test"
  database_name            = "test"
  username                 = "test"
  secret_name              = "payment-test/test-db"
  vpc_id                   = "vpc-0123456789abcdef0"
  subnet_ids               = ["subnet-0aaaaaaaaaaaaaaa1", "subnet-0aaaaaaaaaaaaaaa2", "subnet-0aaaaaaaaaaaaaaa3"]
  client_security_group_id = "sg-0123456789abcdef0"
}

run "keeps_the_database_private_encrypted_and_with_a_standby" {
  command = plan

  assert {
    condition     = aws_db_instance.this.storage_encrypted && !aws_db_instance.this.publicly_accessible
    error_message = "The database must be encrypted and not public."
  }
  assert {
    condition     = aws_db_instance.this.multi_az && aws_db_instance.this.deletion_protection
    error_message = "The database must have a standby in another zone and be protected from deletion."
  }
  assert {
    condition     = one([for p in aws_db_parameter_group.this.parameter : p.value if p.name == "rds.force_ssl"]) == "1"
    error_message = "The database must refuse connections without TLS."
  }
  assert {
    condition     = toset(aws_db_subnet_group.this.subnet_ids) == toset(var.subnet_ids)
    error_message = "The database must live in its own subnets only."
  }
}

run "lets_only_the_client_security_group_in_on_5432" {
  command = plan

  assert {
    condition = (aws_vpc_security_group_ingress_rule.postgres.referenced_security_group_id == var.client_security_group_id
      && aws_vpc_security_group_ingress_rule.postgres.from_port == 5432
    && aws_vpc_security_group_ingress_rule.postgres.to_port == 5432)
    error_message = "Only the client security group may connect, on 5432."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.postgres.cidr_ipv4 == null
    error_message = "No address range may connect, only the client security group."
  }
}

run "sends_the_password_only_through_write_only_arguments" {
  command = plan

  assert {
    condition     = aws_db_instance.this.password == null && aws_db_instance.this.manage_master_user_password != true
    error_message = "The password must go only through password_wo, so that the state never holds it."
  }
  assert {
    condition     = aws_db_instance.this.password_wo_version == aws_secretsmanager_secret_version.this.secret_string_wo_version
    error_message = "The database and its secret must get each new password together."
  }
}

run "refuses_a_single_subnet" {
  command = plan

  variables {
    subnet_ids = ["subnet-0aaaaaaaaaaaaaaa1"]
  }

  expect_failures = [var.subnet_ids]
}
