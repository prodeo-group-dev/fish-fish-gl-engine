# AWS infrastructure for CD

Written 2026-08-22 to close `docs/GL_Production_Readiness_Plan.md`'s Phase 1b
(CD pipeline). Originally unverified (no `terraform` CLI available); once
Terraform was installed the same day, `fmt`/`init`/`validate` were run for
real and pass — `validate` caught a genuine bug (a hand-typed GitHub OIDC
thumbprint that was 39 characters, not the required 40; fixed by fetching it
live via `data "tls_certificate"` instead of hardcoding it at all). A `plan`
with dummy variable values resolved cleanly up to the point of needing real
AWS credentials, which weren't available. **Still never run against real
AWS** — no `plan`/`apply` with real credentials. Review it like any other
partially-verified code before trusting it with real AWS spend.

Deliberately out of scope: **database/RDS provisioning**. This config expects
Postgres to already exist somewhere reachable from the AWS account (an RDS
instance you provision separately, or anywhere else) — `db_host`/`db_user`/
`db_password` are required variables with no defaults for exactly that
reason.

## What this creates

- An ECR repository (`ecr.tf`) with image scanning and a lifecycle policy.
- An ECS cluster running one Fargate task (`ecs.tf`), in the account's
  **default VPC** (`network.tf` — a deliberate simplification, not a hardened
  network design; see that file's own comments).
- An Application Load Balancer over **plain HTTP only** — no domain name or
  ACM certificate exists anywhere in this project yet (`alb.tf`).
- An IAM role GitHub Actions assumes via OIDC — no long-lived AWS keys in
  GitHub secrets (`iam.tf`).
- A Secrets Manager secret for `FISH_DB_PASSWORD` (`secrets.tf`) — the only
  genuinely sensitive value; everything else is a plain task-definition
  environment variable.
- A CloudWatch log group (`logs.tf`).

## First-time setup

1. **Review every `.tf` file first** — this was written without the ability
   to run `terraform validate`, so treat it as a draft, not a known-good plan.

2. `terraform init`

3. Supply the required variables (no defaults — either a `terraform.tfvars`
   file, kept out of git per `.gitignore`, or `-var` flags):
   ```
   db_host      = "<your RDS endpoint or wherever Postgres lives>"
   db_user      = "<db user>"
   db_password  = "<db password>"
   jwt_issuer   = "<your IdP issuer URL>"
   jwt_audience = "<your IdP audience>"
   jwt_jwks_url = "<your IdP JWKS endpoint>"
   ```

4. `terraform plan` — read it carefully. This creates real, billed AWS
   resources (Fargate task, ALB, ECR, CloudWatch Logs, Secrets Manager) —
   roughly $30-40/month at the smallest sizing configured here (`task_cpu`/
   `task_memory` default to the smallest Fargate size; `desired_count = 1`
   means no high availability).

5. `terraform apply`

6. **The first apply won't produce a running, healthy service** — the task
   definition points at an ECR image tag (`bootstrap`) that nothing has ever
   pushed (see `ecs.tf`'s own comment on this). That's expected: the first
   real deploy comes from CI, once step 7 is done and something pushes to
   `master`.

7. Set these as **GitHub Actions repository variables** (Settings → Secrets
   and variables → Actions → Variables tab — not Secrets, none of these are
   sensitive) on `prodeo-group-dev/fish-fish-gl-engine`, using
   `terraform output`:
   - `AWS_REGION` — `eu-west-2`
   - `AWS_DEPLOY_ROLE_ARN` — `terraform output github_actions_deploy_role_arn`
   - `ECR_REPOSITORY` — `terraform output ecr_repository_url`
   - `ECS_CLUSTER` — `terraform output ecs_cluster_name`
   - `ECS_SERVICE` — `terraform output ecs_service_name`
   - `ECS_TASK_DEFINITION_FAMILY` — `terraform output ecs_task_definition_family`
   - `ECS_CONTAINER_NAME` — `terraform output container_name`

8. Push to `master`. `ci.yml`'s `deploy` job should then build, push, and
   deploy a real image — check the Actions run and, once it succeeds,
   `terraform output alb_dns_name` for where to actually reach it.

## Known gaps, flagged rather than silently accepted

- **No HTTPS** — needs a domain + ACM certificate, neither decided yet.
- **No staging environment** — this is a single-environment (`production`)
  setup; `docs/GL_Production_Readiness_Plan.md`'s suggested build order chose
  auto-deploy-to-production over staging-then-promote for now.
- **Public subnet, no NAT Gateway** — a cost/complexity tradeoff (network.tf),
  not a hardened production network.
- **Local Terraform state** — fine for one operator; move to a remote
  backend (S3 + DynamoDB lock table) before more than one person runs
  `terraform apply` against this.
- **`desired_count = 1`** — no high availability. Bumping this alone isn't
  enough either; the ALB/subnets already span the default VPC's AZs, but
  nothing here load-tests or capacity-plans beyond "the smallest Fargate
  size that runs at all."
