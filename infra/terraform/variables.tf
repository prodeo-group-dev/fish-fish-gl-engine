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

# --- Application configuration -------------------------------------
#
# DB/JWT connection details are deliberately variables with no
# defaults, not hard-coded - confirmed 2026-08-22: database/RDS
# provisioning is a separate, bigger decision kept out of this CD
# build's scope (matching docs/GL_Production_Readiness_Plan.md's own
# treatment of the "backup/DR docs... deferred until the managed-
# database choice is made" gap). Point these at wherever Postgres
# actually ends up running - an RDS instance provisioned separately,
# or anywhere else reachable from this VPC.

variable "db_host" {
  description = "FISH_DB_HOST - hostname/endpoint of the Postgres instance this service connects to. No default: must be supplied, since nothing in this config provisions a database."
  type        = string
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
  description = "FISH_DB_USER"
  type        = string
}

variable "db_password" {
  description = "FISH_DB_PASSWORD - stored in AWS Secrets Manager (see secrets.tf), never in plain Terraform state as an environment variable. Sensitive: Terraform still writes it into state in plaintext by design (a well-known Terraform limitation) - use a remote encrypted backend before this holds a real production credential long-term."
  type        = string
  sensitive   = true
}

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
