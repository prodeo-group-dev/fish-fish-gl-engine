# docs/GL_Production_Readiness_Plan.md Phase 1b (CD pipeline) - this
# directory is the AWS infrastructure `.github/workflows/ci.yml`'s
# `deploy` job pushes to. Written 2026-08-22, genuinely unverified:
# this sandbox has no `terraform` CLI and no AWS credentials, so none
# of this has been through `terraform init`/`validate`/`plan`, let
# alone `apply` - same "treat as unverified until run somewhere with
# real tooling" caveat CLAUDE.md already applies to Cowork-sandbox code.
# Run `terraform init && terraform validate` yourself before `apply`.

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # No backend configured - state defaults to local (terraform.tfstate
  # in this directory, gitignored). Fine for a single operator getting
  # this running; move to a remote backend (S3 + DynamoDB lock table)
  # before more than one person ever runs `terraform apply` against
  # this, or the state file becomes a real coordination hazard.
}

provider "aws" {
  region = var.aws_region
}
