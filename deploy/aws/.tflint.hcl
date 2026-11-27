# tflint over prod and the modules (ci/check-terraform.sh): its own rules for Terraform, and the AWS ruleset's, which
# know, for one, which instance types and engine versions exist. Modules from the registry are theirs to lint.
config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}
