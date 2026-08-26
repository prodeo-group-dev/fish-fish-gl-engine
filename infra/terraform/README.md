# AWS infrastructure for CD

Written 2026-08-22 to close `docs/GL_Production_Readiness_Plan.md`'s Phase 1b
(CD pipeline). Originally unverified (no `terraform` CLI available); once
Terraform was installed the same day, `fmt`/`init`/`validate` were run for
real and pass — `validate` caught a genuine bug (a hand-typed GitHub OIDC
thumbprint that was 39 characters, not the required 40; fixed by fetching it
live via `data "tls_certificate"` instead of hardcoding it at all).

**`terraform apply` has succeeded against real AWS** (2026-08-26) — 23
resources created, 0 errors, in the account's `fish-gl-engine` profile (a
scoped `fish-gl-engine-terraform` IAM user, not root — root was only ever
used for the one-time bootstrap of that user and, later, a few policy-version
pushes the scoped user correctly can't do to itself). Getting there took
several real, `apply`-only permission gaps (`plan` doesn't fully simulate
every IAM check `apply` makes) — `ec2:DescribeVpcAttribute`,
`acm:RequestCertificate` (missed entirely on the first pass),
`ec2:DescribeInternetGateways`, `secretsmanager:GetResourcePolicy`,
`elasticloadbalancing:DescribeListenerAttributes`/`ModifyListenerAttributes`,
and `iam:CreateServiceLinkedRole` for both `elasticloadbalancing` and `ecs`
(the ECS one needed a wildcarded resource suffix, unlike ELB's — and even
then, ECS's own internal auto-creation during `CreateService` never worked;
had to create `AWSServiceRoleForECS` directly via
`aws iam create-service-linked-role` once, after which `apply` succeeded
normally) —
all now in `bootstrap-iam-policy.json`. Two operational lessons worth
knowing before running this yourself:

1. **Don't force-kill a stuck-looking `apply`.** One attempt appeared to hang
   on `aws_lb.this` for 40+ minutes with zero output — it wasn't actually
   stuck, the ALB had already finished creating in AWS moments before the
   kill, just hadn't synced back to state yet. Same thing happened to the
   Secrets Manager secret. Both had to be recovered with `terraform import`
   (and, for the secret, `aws secretsmanager restore-secret` first, since it
   had gone into pending-deletion as a side effect). Check AWS directly
   before assuming a long-running `apply` is actually stuck.
2. **ACM's DNS validation record needs to go in the domain's *actual*
   authoritative DNS**, not just any DNS panel that looks plausible. For
   `theprodeogroup.com` specifically, that's Namecheap's own **Advanced DNS**
   tab, not cPanel's Zone Editor (a genuinely separate system, even though
   both are reachable from the same Namecheap account) — confirmed by
   querying the authoritative nameservers (`dns1/dns2.registrar-servers.com`)
   directly rather than trusting propagation-delay assumptions when the
   record didn't show up.

Review this like any other now-verified-but-recently-fragile infrastructure
before making further changes.

**Database provisioning closed and applied, 2026-08-26** (`rds.tf`) —
previously deliberately out of scope, with `db_host`/`db_password` as
required variables pointing at wherever Postgres already lived. Now this
config provisions its own RDS Postgres instance for real (`db-XYMHX226YI...`,
took 6m8s to create). Two more real gaps surfaced at `apply` time, same
pattern as everything else in this file:
- `iam:CreateServiceLinkedRole` for `rds.amazonaws.com` — a third instance
  of the same service-linked-role pattern ELB/ECS already hit; created
  `AWSServiceRoleForRDS` directly, same fix as before.
- **This AWS account is on RDS Free Tier**, which caps automated backup
  retention lower than the 7 days originally configured — reduced to 1 day
  to fit (`FreeTierRestrictionError`, a real account-level constraint, not
  a design choice - revisit once off Free Tier).

**A real consequence of `ecs.tf`'s `ignore_changes` lifecycle rule, worth
knowing before you hit it too**: changing `FISH_DB_HOST` from a variable to
`aws_db_instance.this.address` did NOT update the already-existing task
definition on a plain `apply` — `ignore_changes` treats that attribute as
always matching state, regardless of what the config says. Needed
`terraform apply -replace=aws_ecs_task_definition.this` to force a new
revision with the real DB host — and even then, the ECS *service* is still
running the old revision (its own `ignore_changes = [task_definition]`,
confirmed via `aws ecs describe-services`: `runningCount: 0`, still
referencing `:1`). That's fine, not a bug to chase — the new revision (`:2`,
correct DB host) will be picked up automatically the first time CI's
`deploy` job runs, since it fetches the *current* task definition before
patching in the real image. **This only mattered because this specific
instance already had a task definition from an earlier apply with placeholder
`db_host`/`db_password` values baked in** — a fresh account provisioning
everything for the first time (RDS included, from the very first `apply`)
never hits this, since there's no stale prior revision for `ignore_changes`
to protect in the first place.

`db_host` is gone as a variable entirely (it's
`aws_db_instance.this.address`, computed, not supplied), and `db_password`
is a `random_password` resource, generated once and never typed into a
`-var` flag or `.tfvars` file — closes a real credential-
hygiene gap the old required-variable shape had. `db_user` kept its variable
shape but got a sensible default (`fish_app`) now that this config creates
the user itself.

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
- An RDS Postgres instance (`rds.tf`) — `db.t4g.micro`, 20GB gp3, single-AZ,
  same public-subnet/security-group-locked-down tradeoff already made for
  ECS (not publicly accessible either way). 1-day automated backups
  (capped by RDS Free Tier on this account, confirmed at apply time -
  originally intended 7, revisit once off Free Tier). Master password
  is a `random_password` resource, stored directly in the Secrets Manager
  secret above — never passed as a plain variable.

## First-time setup

**Already done for this AWS account (827709230476, eu-west-2)** as of
2026-08-26 — 27 resources exist (23 from the original apply + 4 from RDS),
`terraform state list` will show them. The steps below are for reference
(e.g. a fresh account) or if starting over.

1. **Review every `.tf` file first.**

2. `terraform init`

3. Supply the required variables (no defaults — either a `terraform.tfvars`
   file, kept out of git per `.gitignore`, or `-var` flags). `db_host`/
   `db_password` are gone — this config provisions its own RDS instance and
   generates its own password (`rds.tf`), so only the JWT settings (an
   external IdP this config can't provision or guess) are actually required:
   ```
   jwt_issuer   = "<your IdP issuer URL>"
   jwt_audience = "<your IdP audience>"
   jwt_jwks_url = "<your IdP JWKS endpoint>"
   ```
   `db_user`, `db_instance_class`, `db_allocated_storage`,
   `db_backup_retention_days` all have sensible defaults - override only if
   you want something other than the smallest Free-Tier-friendly baseline.

4. `terraform plan` — read it carefully. This creates real, billed AWS
   resources (Fargate task, ALB, ACM certificate, ECR, CloudWatch Logs,
   Secrets Manager, RDS Postgres) — roughly $30-40/month at the smallest
   sizing configured here (`task_cpu`/`task_memory`/`db_instance_class`
   default to the smallest size in each case; `desired_count = 1` and
   `multi_az = false` mean no high availability anywhere).

5. `terraform apply` (**first pass** — the HTTPS listener can't be created
   yet, since the ACM certificate starts in `PENDING_VALIDATION`; everything
   else gets created).

6. Add the DNS validation record at **theprodeogroup.com's external DNS
   provider** (not Route 53 in this account) — `terraform output
   acm_validation_record` gives the exact CNAME name/type/value ACM
   generated. **For theprodeogroup.com specifically, this means Namecheap's
   own "Advanced DNS" tab (Domain List → Manage → Advanced DNS) — not cPanel's
   Zone Editor**, which is a separate system and won't reach the domain's
   actual authoritative nameservers (`dns1/dns2.registrar-servers.com`).
   Confirm the record is live by querying the authoritative nameserver
   directly rather than waiting on propagation assumptions:
   `nslookup -type=CNAME <record-name> dns1.registrar-servers.com`.

7. `terraform apply` **again** — this time `aws_acm_certificate_validation`
   should find the certificate `ISSUED` and create the HTTPS listener.

8. Add a second DNS record at the same external provider (again, Advanced
   DNS for theprodeogroup.com, not cPanel): a CNAME for
   `capital.theprodeogroup.com` pointing at `terraform output alb_dns_name`.

9. **The service still won't be healthy yet** — the task definition points
   at an ECR image tag (`bootstrap`) that nothing has ever pushed (see
   `ecs.tf`'s own comment on this). That's expected: the first real deploy
   comes from CI, once step 10 is done and something pushes to `master`.

10. **Done for this instance (2026-08-26), via `gh variable set`.** Set these
    as **GitHub Actions repository variables** (Settings → Secrets
    and variables → Actions → Variables tab — not Secrets, none of these are
    sensitive) on `prodeo-group-dev/fish-fish-gl-engine`, using
    `terraform output`:
    - `AWS_REGION` — `eu-west-2`
    - `AWS_DEPLOY_ROLE_ARN` — `arn:aws:iam::827709230476:role/fish-gl-engine-github-actions-deploy`
    - `ECR_REPOSITORY` — `827709230476.dkr.ecr.eu-west-2.amazonaws.com/fish-gl-engine`
    - `ECS_CLUSTER` — `fish-gl-engine-production`
    - `ECS_SERVICE` — `fish-gl-engine-production`
    - `ECS_TASK_DEFINITION_FAMILY` — `fish-gl-engine`
    - `ECS_CONTAINER_NAME` — `fish-gl-engine`

11. **Not yet done — the actual next step.** Push to `master`. `ci.yml`'s
    `deploy` job should then build, push, and deploy a real image — check
    the Actions run and, once it succeeds, `https://capital.theprodeogroup.com`
    for where to actually reach it. The database side is ready (real RDS
    instance, real generated password already in the task definition's
    latest revision) — the only remaining placeholder is JWT config
    (`dummy-idp.example.com`), since this config still has no real IdP to
    point at.

## Known gaps, flagged rather than silently accepted

- **No staging environment** — this is a single-environment (`production`)
  setup; `docs/GL_Production_Readiness_Plan.md`'s suggested build order chose
  auto-deploy-to-production over staging-then-promote for now.
- **Public subnet, no NAT Gateway** — a cost/complexity tradeoff (network.tf),
  not a hardened production network. Applies to RDS too now (`rds.tf`) - not
  publicly accessible, but not network-isolated either.
- **Local Terraform state** — fine for one operator; move to a remote
  backend (S3 + DynamoDB lock table) before more than one person runs
  `terraform apply` against this.
- **`desired_count = 1` / `multi_az = false`** — no high availability
  anywhere, compute or database. Bumping `desired_count` alone isn't enough
  either; the ALB/subnets already span the default VPC's AZs, but nothing
  here load-tests or capacity-plans beyond "the smallest size that runs at
  all."
- **RDS backup retention capped at 1 day** by this AWS account's Free Tier
  status (confirmed at `apply` time, not a design choice) - the original
  intent was 7 days; revisit once the account moves off Free Tier.
- **`skip_final_snapshot = true` / `deletion_protection = false`** on the RDS
  instance — correct only while it holds no real data (still true as of
  first apply). Flip both once real data exists — not automatically
  revisited by this document, see `rds.tf`'s own comment.
