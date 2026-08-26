# AWS infrastructure for CD

Written 2026-08-22 to close `docs/GL_Production_Readiness_Plan.md`'s Phase 1b
(CD pipeline). Originally unverified (no `terraform` CLI available); once
Terraform was installed the same day, `fmt`/`init`/`validate` were run for
real and pass — `validate` caught a genuine bug (a hand-typed GitHub OIDC
thumbprint that was 39 characters, not the required 40; fixed by fetching it
live via `data "tls_certificate"` instead of hardcoding it at all).

**`terraform plan` now genuinely succeeds against real AWS** (2026-08-26) —
set up a scoped `fish-gl-engine-terraform` IAM user (moved off root
credentials once `aws login` surfaced it had authenticated as the account
root user) with a least-privilege customer-managed policy
(`bootstrap-iam-policy.json`). First `plan` got to 16 planned resources
before stopping on a genuinely missing permission (`ec2:DescribeVpcAttribute`
— a different action from `ec2:DescribeVpcs`, needed by the `aws_vpc` data
source) — fixed in the policy file, pushing the new policy version needed a
privileged identity (a second, separate one-time root session, since the
scoped user correctly can't modify its own permissions). Re-ran clean:
**23 resources planned, 0 errors.** **Still no successful `terraform apply`**
— `plan` succeeding is a real signal, but nothing has actually been created
in AWS yet beyond the IAM bootstrap itself. Review it like any other
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
- An Application Load Balancer (`alb.tf`) with a real domain and HTTPS —
  `capital.theprodeogroup.com` (confirmed 2026-08-26), an ACM certificate
  (`acm.tf`, DNS-validated), HTTP redirecting to HTTPS. **theprodeogroup.com's
  DNS is external** (not Route 53 in this account), so this is a genuine
  two-phase `apply` — see "First-time setup" step 4 below.
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
   resources (Fargate task, ALB, ACM certificate, ECR, CloudWatch Logs,
   Secrets Manager) — roughly $30-40/month at the smallest sizing configured
   here (`task_cpu`/`task_memory` default to the smallest Fargate size;
   `desired_count = 1` means no high availability).

5. `terraform apply` (**first pass** — the HTTPS listener can't be created
   yet, since the ACM certificate starts in `PENDING_VALIDATION`; everything
   else gets created).

6. Add the DNS validation record at **theprodeogroup.com's external DNS
   provider** (not Route 53 in this account) — `terraform output
   acm_validation_record` gives the exact CNAME name/type/value ACM
   generated. Wait for it to propagate (usually minutes, occasionally longer).

7. `terraform apply` **again** — this time `aws_acm_certificate_validation`
   should find the certificate `ISSUED` and create the HTTPS listener.

8. Add a second DNS record at the same external provider: a CNAME for
   `capital.theprodeogroup.com` pointing at `terraform output alb_dns_name`.

9. **The service still won't be healthy yet** — the task definition points
   at an ECR image tag (`bootstrap`) that nothing has ever pushed (see
   `ecs.tf`'s own comment on this). That's expected: the first real deploy
   comes from CI, once step 10 is done and something pushes to `master`.

10. Set these as **GitHub Actions repository variables** (Settings → Secrets
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

11. Push to `master`. `ci.yml`'s `deploy` job should then build, push, and
    deploy a real image — check the Actions run and, once it succeeds,
    `https://capital.theprodeogroup.com` for where to actually reach it.

## Known gaps, flagged rather than silently accepted

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
