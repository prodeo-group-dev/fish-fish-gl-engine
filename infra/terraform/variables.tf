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

variable "root_domain" {
  description = "The registered domain itself (not the capital. subdomain domain_name points at) - used by notifications.tf's SES identity (mail.<root_domain>). Same external-DNS-provider caveat as domain_name applies to its verification/DKIM records."
  type        = string
  default     = "theprodeogroup.com"
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

# --- POP (Purchase Order Processing) service (pop.tf) -----------------
#
# 2026-08-30: POP was fully built and tested this session (149 unit +
# 14 integration tests) but had no AWS infrastructure at all until now.
# Reuses GL's VPC/ECS cluster/ALB/Cognito pool wherever sharing costs
# nothing (clusters and Cognito are free; a second RDS instance would
# blow this account's Free Tier hours) - dedicated resources only where
# sharing would be wrong (IAM roles, the database itself, the OIDC
# deploy role, the ACM certificate - see pop.tf's own notes).

variable "pop_short_name" {
  description = "Short identifier for POP resources with tight AWS length limits (aws_lb_target_group.name caps at 32 chars - 'fish-purchase-order-processing-production' would fail at apply)."
  type        = string
  default     = "fish-pop"
}

variable "pop_domain_name" {
  description = "FQDN POP is reachable at - a dedicated subdomain, not a path on GL's own domain, since POP is a separate service with its own ALB listener rule and ACM certificate."
  type        = string
  default     = "pop-api.theprodeogroup.com"
}

variable "pop_github_repository" {
  description = "owner/repo whose GitHub Actions workflows are allowed to assume POP's own deploy IAM role via OIDC - cannot reuse GL's role, since the trust policy's sub condition is hardcoded per-repo."
  type        = string
  default     = "prodeo-group-dev/fish-purchase-order-processing"
}

variable "pop_github_oidc_subject" {
  description = "The OIDC subject claim allowed to assume POP's deploy role - restricts deploys to pushes on master specifically. See iam.tf's github_oidc_subject for the GL equivalent."
  type        = string
  default     = "repo:prodeo-group-dev/fish-purchase-order-processing:ref:refs/heads/master"
}

variable "pop_db_name" {
  description = "POP_DB_NAME - a new database on GL's existing RDS instance (rds.tf), not a second instance. Created via a manual step (pop.tf's own note) - Terraform has no native way to add a second logical database to an already-running instance."
  type        = string
  default     = "pop_production"
}

variable "pop_db_user" {
  description = "POP_DB_USER - a dedicated user, not GL's own FISH_DB_USER credentials, for isolation between the two services."
  type        = string
  default     = "pop_app"
}

variable "pop_gl_engine_tenant_id" {
  description = "POP_GL_ENGINE_TENANT_ID - Prodeo Group's real tenant UUID in GL's own system, looked up live via GET /me while signed in as the Prodeo Group admin (2026-08-30), not invented."
  type        = string
  default     = "9fa2198b-2a6f-467d-97ac-6f6fbce6a9fd"
}

variable "pop_gl_engine_company_id" {
  description = "POP_GL_ENGINE_COMPANY_ID - Prodeo Group's real company UUID in GL's own system (used by the fulfilment pipeline's /match and /pay routes to resolve GL's purchase-posting-context), same source as pop_gl_engine_tenant_id: a real authenticated GET /me call, not invented (2026-09-01)."
  type        = string
  default     = "2ee7984b-1817-4148-ad04-653df9de724a"
}

variable "pop_notification_from_domain" {
  description = "The verified SES sender domain POP's eOrder emails go out from (2026-08-31) - the same domain identity notifications.tf already requests for Cognito (mail.theprodeogroup.com), not a new one."
  type        = string
  default     = "mail.theprodeogroup.com"
}

# --- SOP (Sales Order Processing) infrastructure (sop.tf) -------------
#
# 2026-08-31: SOP got a real HTTP server and, the same day, real
# Customer persistence (sop_production/sop_app on GL's shared RDS
# instance, provisioned manually, same reasoning as POP's own db
# variables below). Same sharing/dedication split as POP's own block.

variable "sop_short_name" {
  description = "Short identifier for SOP resources with tight AWS length limits (aws_lb_target_group.name caps at 32 chars)."
  type        = string
  default     = "fish-sop"
}

variable "sop_domain_name" {
  description = "FQDN SOP is reachable at - a dedicated subdomain, own ALB listener rule and ACM certificate, same pattern as POP's own."
  type        = string
  default     = "sop-api.theprodeogroup.com"
}

variable "sop_github_repository" {
  description = "owner/repo whose GitHub Actions workflows are allowed to assume SOP's own deploy IAM role via OIDC - cannot reuse GL's or POP's role, trust policy sub condition is hardcoded per-repo."
  type        = string
  default     = "prodeo-group-dev/fish-sales-order-processing"
}

variable "sop_github_oidc_subject" {
  description = "The OIDC subject claim allowed to assume SOP's deploy role - restricts deploys to pushes on master specifically."
  type        = string
  default     = "repo:prodeo-group-dev/fish-sales-order-processing:ref:refs/heads/master"
}

variable "sop_db_name" {
  description = "SOP_DB_NAME - a new database on GL's existing RDS instance (rds.tf), not a second instance. Created via a manual step 2026-08-31, same as POP's own db_name."
  type        = string
  default     = "sop_production"
}

variable "sop_db_user" {
  description = "SOP_DB_USER - a dedicated user, not GL's or POP's own credentials, for isolation between services."
  type        = string
  default     = "sop_app"
}

variable "sop_gl_engine_tenant_id" {
  description = "SOP_GL_ENGINE_TENANT_ID - Prodeo Group's real tenant UUID in GL's own system, same value as pop_gl_engine_tenant_id (both call the same GL Engine as the same tenant)."
  type        = string
  default     = "9fa2198b-2a6f-467d-97ac-6f6fbce6a9fd"
}

variable "sop_service_account_email" {
  description = "The Cognito username/email for SOP's service-account identity (sop_service_account.tf) - resolves SOP_GL_ENGINE_BEARER_TOKEN. Never sent an email (message_action = SUPPRESS), so this doesn't need to be a real, monitored mailbox."
  type        = string
  default     = "sop-service@theprodeogroup.com"
}

variable "im_gl_engine_tenant_id" {
  description = "IM_GL_ENGINE_TENANT_ID - Prodeo Group's real tenant UUID in GL's own system, same value as pop_gl_engine_tenant_id/sop_gl_engine_tenant_id (all three call the same GL Engine as the same tenant)."
  type        = string
  default     = "9fa2198b-2a6f-467d-97ac-6f6fbce6a9fd"
}

variable "im_gl_engine_company_id" {
  description = "IM_GL_ENGINE_COMPANY_ID - same value as pop_gl_engine_company_id, Prodeo Group's real company UUID in GL's own system."
  type        = string
  default     = "2ee7984b-1817-4148-ad04-653df9de724a"
}

variable "im_service_account_email" {
  description = "The Cognito username/email for IM's service-account identity (im_service_account.tf) - resolves IM_GL_ENGINE_BEARER_TOKEN. Never sent an email (message_action = SUPPRESS), so this doesn't need to be a real, monitored mailbox."
  type        = string
  default     = "im-service@theprodeogroup.com"
}

variable "pop_im_service_account_email" {
  description = "The Cognito username/email for POP's service-account identity for calling IM (pop_service_account.tf) - resolves POP_IM_SERVICE_ACCOUNT_USERNAME/PASSWORD. Never sent an email (message_action = SUPPRESS), so this doesn't need to be a real, monitored mailbox."
  type        = string
  default     = "pop-im-service@theprodeogroup.com"
}

variable "sop_im_service_account_email" {
  description = "The Cognito username/email for SOP's service-account identity for calling IM (sop_im_service_account.tf) - resolves SOP_IM_SERVICE_ACCOUNT_USERNAME/PASSWORD. Never sent an email (message_action = SUPPRESS), so this doesn't need to be a real, monitored mailbox."
  type        = string
  default     = "sop-im-service@theprodeogroup.com"
}

variable "im_short_name" {
  description = "Short identifier for IM's length-constrained AWS resources (ALB target group name has a 32-char limit) - same reasoning as pop_short_name."
  type        = string
  default     = "fish-im"
}

variable "im_domain_name" {
  description = "FQDN IM is reachable at - a dedicated subdomain, matching pop_domain_name's own reasoning (a separate service with its own ALB listener rule and ACM certificate, not a path on GL's own domain)."
  type        = string
  default     = "im-api.theprodeogroup.com"
}

variable "im_db_name" {
  description = "IM's own Postgres database name on the shared RDS instance (rds.tf) - separate logical database, own credentials, same reasoning as pop_db_name."
  type        = string
  default     = "im_production"
}

variable "im_db_user" {
  description = "IM's own Postgres user - separate credentials from GL's/POP's/SOP's own, same reasoning as pop_db_user."
  type        = string
  default     = "im_app"
}

variable "im_github_oidc_subject" {
  description = "The OIDC subject claim allowed to assume IM's deploy role - restricts deploys to pushes on master specifically. See iam.tf's github_oidc_subject for the GL equivalent."
  type        = string
  default     = "repo:prodeo-group-dev/fish-inventory-management:ref:refs/heads/master"
}

# --- Self-hosted GitHub Actions runner (github_runner.tf) ------------
#
# 2026-08-30: GitHub Actions' hosted runners have been disabled
# account-wide for prodeo-group-dev since 2026-08-26 (abuse-detection
# review, see github-actions-support-followup-draft.txt) - this is the
# scoped fix, not a git-hosting migration (OIDC federation, iam.tf,
# needs zero changes: the runner - hosted or self-hosted - is what
# requests the token from token.actions.githubusercontent.com and
# presents it to AWS STS, confirmed against GitHub's own OIDC flow and
# aws-actions/configure-aws-credentials#453 before writing this).

variable "github_organization" {
  description = "The GitHub org every repo in this project lives under - the runner registers at this level (not per-repo) so one instance can serve all 8 repos."
  type        = string
  default     = "prodeo-group-dev"
}

variable "runner_instance_type" {
  description = "EC2 instance type for the self-hosted runner - t3.large (2 vCPU/8GB), deliberately larger than this config's other 'smallest reasonable' defaults (task_cpu, db_instance_class): Gradle/Kotlin compilation running alongside a `docker build` in the same job is genuinely memory-hungry, confirmed by this project's own local build experience."
  type        = string
  default     = "t3.large"
}

variable "runner_root_volume_size" {
  description = "Root EBS volume size (GB) for the runner instance - Docker image layers, Gradle caches, and multiple repos' checkouts accumulate across builds; the AMI's own default (~8GB) isn't enough."
  type        = number
  default     = 40
}

# --- Application configuration (JWT) ---------------------------------
#
# Provisioned by this config as of 2026-08-26 (cognito.tf) - previously
# deliberately out of scope, same reasoning that applied to db_host/
# db_password before rds.tf: jwt_issuer/jwt_audience/jwt_jwks_url used
# to be required variables with no default ("this config has no way to
# provision or guess an external IdP"). Now that a real external IdP
# (Amazon Cognito) is provisioned directly, they're computed values -
# see cognito.tf's own `locals` block - not variables at all anymore.
