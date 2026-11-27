terraform {
  # 1.11 for write-only arguments, which keep the databases' passwords out of the state.
  required_version = ">= 1.11"

  # Exact versions, so every init gets the same providers: aws and random for this configuration, the rest for the
  # eks module.
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.74.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "3.9.1"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "4.4.2"
    }
    time = {
      source  = "hashicorp/time"
      version = "0.14.2"
    }
    cloudinit = {
      source  = "hashicorp/cloudinit"
      version = "2.4.1"
    }
    null = {
      source  = "hashicorp/null"
      version = "3.3.2"
    }
  }

  # Never applied from this repository, so the bucket is only an example; S3 itself locks the state (use_lockfile).
  backend "s3" {
    bucket       = "payment-gateway-terraform-state"
    key          = "prod/terraform.tfstate"
    region       = "eu-central-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "payment-gateway"
      Environment = "prod"
      ManagedBy   = "terraform"
    }
  }
}
