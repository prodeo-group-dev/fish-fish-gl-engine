# GL — AWS Agent Guidance

Scoped to this repo specifically, not `fish`'s top-level `CLAUDE.md`. **Not
exclusive to GL** — this guidance applies equally to `EA`/`POP`/`SOP`/`IM`/
`HR`, each of which does its own real AWS work (`aws ecr`/`aws ecs` calls)
from its own `Jenkinsfile`, mirroring the pattern first built here.

**The shared Terraform project that used to live at `GL/infra/terraform/`
moved to its own repo, `fish-infrastructure`, 2026-09-17** — see
`FiSH/docs/Platform_Infrastructure_Extraction_Design.md`. It provisions
every sibling's infrastructure (`ea.tf`, `pop.tf`, `sop.tf`, `im.tf`,
`hr.tf` alongside GL's own resources), not just GL's, and never belonged
bundled with this repo's application code. `GL/` itself now has no
infrastructure-as-code of its own at all. The self-hosted Jenkins pipeline
(`Jenkinsfile`, one per repo) is each service's actual, working CI/CD path
today, not `.github/workflows/pipeline.yml` — that GitHub Actions
workflow's own header documents push/pull_request events getting stuck and
not triggering runs, which is why Jenkins was built in the first place.
The top-level `CLAUDE.md` stays this project's own narrated multi-repo
history; this file is generic AWS-agent operational guidance, kept
separate so the two don't blend.

**Source**: fetched 2026-08-26 from `https://raw.githubusercontent.com/aws/agent-toolkit-for-aws/refs/heads/main/rules/aws-agent-rules.md`
(Step 7 of AWS's own Agent Toolkit setup instructions, "advanced AWS experience"
variant), with one deliberate adaptation — see below.

## AWS Guidance

- Prefer the AWS MCP Server for AWS interactions — it provides sandboxed
  execution, observability, and audit logging. If unavailable, use the
  AWS CLI directly.
- Before starting a task, check whether a relevant AWS skill is available.
  Load the skill with `retrieve_skill` and prefer its guidance over
  general knowledge.
- When uncertain about specific AWS details (API parameters, permissions,
  limits, error codes), verify against documentation rather than guessing.
  State uncertainty explicitly if you cannot confirm.
- When creating infrastructure, prefer infrastructure-as-code. **Adapted
  from the source rule** (which said "AWS CDK or CloudFormation") — this
  project already uses **Terraform** (the `fish-infrastructure` repo,
  confirmed 2026-08-22 in `docs/GL_Production_Readiness_Plan.md`'s Phase
  1b, relocated there 2026-09-17), so the underlying intent (IaC over ad
  hoc CLI commands) is kept, but the specific tool named is corrected to
  match what's actually in use here. Don't introduce CDK/CloudFormation
  alongside it without a deliberate decision to do so.
- When working with infrastructure, follow AWS Well-Architected Framework
  principles.
- Do not use em dashes in AWS resource names or descriptions. Use
  hyphens instead.

## Secret Safety

- MUST load the `aws-secrets-manager` skill first for any secret,
  credential, API key, token, or password task. MUST NOT call
  `secretsmanager get-secret-value` or `batch-get-secret-value`, and MUST
  NOT hit the Secrets Manager Agent daemon directly. MUST use
  `{{resolve:secretsmanager:secret-id:SecretString:json-key}}` with
  `asm-exec` so the secret resolves at runtime without entering context.
