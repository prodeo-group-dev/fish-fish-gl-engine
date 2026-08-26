# GL — AWS Agent Guidance

Scoped to this repo specifically, not `fish`'s top-level `CLAUDE.md` — `GL/` is the
only repo in this project that does AWS work (`infra/terraform/`, `.github/workflows/pipeline.yml`'s
`deploy` job). The top-level `CLAUDE.md` stays this project's own narrated
multi-repo history; this file is generic AWS-agent operational guidance, kept
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
  project already uses **Terraform** (`infra/terraform/`, confirmed
  2026-08-22 in `docs/GL_Production_Readiness_Plan.md`'s Phase 1b), so the
  underlying intent (IaC over ad hoc CLI commands) is kept, but the specific
  tool named is corrected to match what's actually in use here. Don't
  introduce CDK/CloudFormation alongside it without a deliberate decision to
  do so.
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
