variable "aws_region" {
  description = "AWS region - eu-west-2 (London), confirmed 2026-08-22: Purse/the UK credit union business is this project's primary near-term market."
  type        = string
  default     = "eu-west-2"
}

variable "project_name" {
  description = "Short name used to prefix every resource this config creates."
  type        = string
  default     = "fish-gl-engine"
}

variable "environment" {
  description = "Deployment environment name - single-environment for now (docs/GL_Production_Readiness_Plan.md's suggested build order chose auto-deploy-to-production over staging-then-promote, 2026-08-22, since no staging environment exists yet)."
  type        = string
  default     = "production"
}

variable "github_repository" {
  description = "owner/repo whose GitHub Actions workflows are allowed to assume the deploy IAM role via OIDC - deliberately narrow (not a wildcard), and further restricted to the master branch by github_oidc_subject below."
  type        = string
  default     = "prodeo-group-dev/fish-fish-gl-engine"
}

variable "github_oidc_subject" {
  description = "The OIDC subject claim allowed to assume the deploy role - restricts deploys to pushes on master specifically, not any branch or PR from this repo. See iam.tf's trust policy."
  type        = string
  default     = "repo:prodeo-group-dev/fish-fish-gl-engine:ref:refs/heads/master"
}

variable "container_port" {
  description = "Port the container listens on - matches Dockerfile's EXPOSE 8080 / Application.kt's FISH_HTTP_PORT default."
  type        = number
  default     = 8080
}

variable "task_cpu" {
  description = "Fargate task-level CPU units (256 = 0.25 vCPU) - deliberately the smallest Fargate size to start; this is a starting baseline for CD to exist at all, not a capacity-planned production sizing."
  type        = number
  default     = 256
}

variable "task_memory" {
  description = "Fargate task-level memory in MiB - 512 pairs with 256 CPU units per AWS Fargate's supported combinations."
  type        = number
  default     = 512
}

variable "desired_count" {
  description = "Number of running tasks - 1, matching this being a CD-exists baseline, not a high-availability setup. Two AZs behind the ALB would need desired_count >= 2 to actually tolerate an AZ failure."
  type        = number
  default     = 1
}

variable "domain_name" {
  description = "FQDN this service is reachable at - confirmed 2026-08-26: capital.theprodeogroup.com. theprodeogroup.com's DNS lives at an external registrar/DNS provider, NOT Route 53 in this AWS account - so this config can request the ACM certificate and build the HTTPS listener, but cannot create the DNS records itself. See outputs.tf's acm_validation_record and alb_dns_name - both need to be added manually at the external DNS provider (the validation CNAME first, then a CNAME for domain_name itself pointing at the ALB, once the certificate is ISSUED)."
  type        = string
  default     = "capital.theprodeogroup.com"
}

# --- Database (rds.tf) ----------------------------------------------
#
# Provisioned by this config as of 2026-08-26 - previously deliberately
# out of scope (db_host/db_password were required external variables,
# no default, "point these at wherever Postgres actually ends up
# running"). Closed docs/GL_Production_Readiness_Plan.md's last open
# item for Phase 1b. db_host and db_password are no longer variables -
# db_host is now `aws_db_instance.this.address` (computed, not
# supplied), and db_password is a `random_password` resource (rds.tf),
# generated once at first apply and never typed into a -var flag or
# .tfvars file at all - closes a real, if minor, credential-hygiene gap
# the old required-variable shape had (a password passed on the CLI is
# visible in shell history and the process list).

variable "db_instance_class" {
  description = "RDS instance class - db.t4g.micro, the smallest/cheapest ARM-based option, matching this config's existing 'smallest reasonable size, not capacity-planned' pattern (see task_cpu/task_memory's own comments)."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "RDS allocated storage in GB - 20, the minimum RDS allows, matching the same minimal-baseline philosophy."
  type        = number
  default     = 20
}

variable "db_engine_version" {
  description = "Postgres major version - matches the version this codebase's CI already runs against (pipeline.yml's integration-test job uses postgres:16)."
  type        = string
  default     = "16"
}

variable "db_backup_retention_days" {
  description = "RDS automated backup retention. Confirmed 2026-08-26: this AWS account is on RDS Free Tier, which caps backup retention lower than the 7 days originally intended here - reduced to 1 to fit within it (a real account-level constraint discovered at apply time, not a design choice). Revisit once the account moves off Free Tier."
  type        = number
  default     = 1
}

variable "db_port" {
  description = "FISH_DB_PORT"
  type        = string
  default     = "5432"
}

variable "db_name" {
  description = "FISH_DB_NAME"
  type        = string
  default     = "fish_production"
}

variable "db_user" {
  description = "FISH_DB_USER - the RDS master username. Given a sensible default now that this config provisions the instance itself, rather than requiring the caller to already know a username for a database that doesn't exist yet."
  type        = string
  default     = "fish_app"
}

# --- Application configuration (JWT) ---------------------------------
#
# Unlike DB connection details, JWT settings stay required variables
# with no defaults - they describe an external IdP this config has no
# way to provision or guess, unlike the database.

variable "jwt_issuer" {
  description = "FISH_JWT_ISSUER - the external IdP's issuer URL (Auth.kt)."
  type        = string
}

variable "jwt_audience" {
  description = "FISH_JWT_AUDIENCE"
  type        = string
}

variable "jwt_jwks_url" {
  description = "FISH_JWT_JWKS_URL - the external IdP's JWKS endpoint, fetched in-process (confirmed over an API Gateway authorizer, docs/DDD_Design.md Section 10.19)."
  type        = string
}
