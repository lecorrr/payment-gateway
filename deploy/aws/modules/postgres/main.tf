# A PostgreSQL on RDS for one app: Multi-AZ, encrypted, TLS only, reachable from the EKS nodes alone, with the
# master user's password in a Secrets Manager secret, where External Secrets reads it for the app.

# Made anew at every plan and apply and never kept in the state. The database and the secret take it through
# write-only arguments, which Terraform sends only when password_version changes, so the two always get the same one.
ephemeral "random_password" "master" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

resource "aws_db_parameter_group" "this" {
  name   = var.name
  family = "postgres17"

  # Connections without TLS are refused; the apps connect with sslmode=require.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_security_group" "this" {
  name        = "${var.name}-db"
  description = "PostgreSQL of ${var.name}, from the EKS nodes only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "postgres" {
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = var.client_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL from the EKS nodes"
}

resource "aws_db_instance" "this" {
  identifier = var.name
  engine     = "postgres"
  # The major version of compose.yaml and the tests' postgres:17-alpine; RDS keeps the minor version current.
  engine_version = "17"
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name             = var.database_name
  username            = var.username
  password_wo         = ephemeral.random_password.master.result
  password_wo_version = var.password_version

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  # A standby in another zone, which RDS fails over to.
  multi_az = true

  backup_retention_period   = var.backup_retention_days
  copy_tags_to_snapshot     = true
  deletion_protection       = true
  final_snapshot_identifier = "${var.name}-final"

  performance_insights_enabled    = true
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
}

resource "aws_secretsmanager_secret" "this" {
  name        = var.secret_name
  description = "User and password of the ${var.name} database, which External Secrets puts into the app's Secret"
}

resource "aws_secretsmanager_secret_version" "this" {
  secret_id                = aws_secretsmanager_secret.this.id
  secret_string_wo         = jsonencode({ username = var.username, password = ephemeral.random_password.master.result })
  secret_string_wo_version = var.password_version
}
