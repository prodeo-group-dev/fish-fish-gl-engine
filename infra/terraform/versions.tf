# docs/GL_Production_Readiness_Plan.md Phase 1b (CD pipeline) - this
# directory is the AWS infrastructure `.github/workflows/pipeline.yml`'s
# `deploy` job pushes to. Written 2026-08-22 with no `terraform` CLI
# available, so genuinely unverified at the time; `terraform init`/
# `fmt`/`validate` were run for real the same day once Terraform was
# installed (see iam.tf's own note on what `validate` caught). `plan`/
# `apply` still haven't run - no AWS credentials available yet.

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
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
