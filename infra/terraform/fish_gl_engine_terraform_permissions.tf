# Two read gaps found via a full `terraform apply -refresh-only` sweep
# across this project (2026-09-17): the `fish-gl-engine-terraform` IAM
# user (the identity Terraform itself runs as) couldn't read either
# resource below, so every plan/apply surfaced an AccessDenied error
# on both instead of completing cleanly, leaving their own state
# unverifiable.
#
# The user already exists in IAM outside this stack (same convention
# as claude_readonly.tf) - this file only attaches a policy, it does
# not recreate the user.
#
# Deliberately plain string literals below, not a `data "aws_iam_user"`
# lookup or a resource reference to `aws_cloudfront_function.pitch_basic_auth`
# - either one makes *this plan itself* fail to generate, since Terraform
# reads referenced data sources/resources up front regardless of
# -target, and `fish-gl-engine-terraform` lacks exactly the permissions
# this file exists to grant it (confirmed: it can't even run
# `iam:GetUser` on itself). A plain ARN/username string has no such
# read dependency.
#
# **ManageOwnInlinePolicies, added the same day**: this same apply also
# hit a harder wall - `fish-gl-engine-terraform` can't grant itself any
# new permission at all (`iam:PutUserPolicy` on itself was denied too),
# so even applying the two statements above required switching to the
# root/bootstrap credential once. Rather than needing that same root
# detour for every *future* narrow permission gap of this exact shape,
# this statement lets `fish-gl-engine-terraform` manage its own inline
# policy going forward via an ordinary `terraform apply` under its own
# normal credentials - scoped strictly to its own user ARN, so it still
# can't touch any other IAM identity or grant itself anything broader
# than "edit my own inline policy." One root-level apply now closes this
# entire recurring pattern, not just today's two statements.
#
# **A managed policy, not an inline one** - `fish-gl-engine-terraform`
# already carries three pre-existing inline policies
# (`apex-redirect-cloudfront`, `pitch-deck-cloudfront-access`,
# `UpdateProdeoGroupWebsiteDistribution` - see the still-open
# `project_apex_domain_iam_cleanup_deferred` memory) that already
# consume nearly all of an IAM user's 2048-byte aggregate inline-policy
# quota; a fourth inline policy hit that ceiling on the first attempt.
# This user's *other* added permissions are all already separate
# managed policies (`fish-gl-engine-terraform-frontend`, `-notifications`,
# `-bootstrap`, etc.), so this follows that same established convention
# rather than fighting the inline quota.

resource "aws_iam_policy" "fish_gl_engine_terraform_refresh_reads" {
  name = "fish-gl-engine-terraform-refresh-reads"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadClaudeReadonlyUser"
        Effect   = "Allow"
        Action   = "iam:GetUser"
        Resource = "arn:aws:iam::827709230476:user/claude-readonly"
      },
      {
        # DescribeFunction alone wasn't enough - refreshing this
        # resource also needs GetFunction (fetches the function's
        # code, not just its metadata), discovered on the first
        # post-fix refresh attempt.
        Sid    = "ReadPitchBasicAuthFunction"
        Effect = "Allow"
        Action = [
          "cloudfront:DescribeFunction",
          "cloudfront:GetFunction",
        ]
        Resource = "arn:aws:cloudfront::827709230476:function/fish-gl-engine-pitch-basic-auth"
      },
      {
        Sid    = "ManageOwnInlinePolicies"
        Effect = "Allow"
        Action = [
          "iam:GetUser",
          "iam:PutUserPolicy",
          "iam:GetUserPolicy",
          "iam:DeleteUserPolicy",
          "iam:ListUserPolicies",
        ]
        Resource = "arn:aws:iam::827709230476:user/fish-gl-engine-terraform"
      },
      {
        # Switching to a managed policy (see the comment above) opened
        # a second gap of the exact same shape: `fish-gl-engine-terraform`
        # couldn't read or update its own managed policy either
        # (`iam:GetPolicy` denied on this very resource during the
        # first `terraform import` attempt). Scoped to the
        # `fish-gl-engine-terraform-*` naming convention already used
        # for this user's other managed policies, not just this one
        # ARN, so adding the *next* narrow permission gap this way
        # doesn't need a third root visit either.
        Sid    = "ManageOwnManagedPolicies"
        Effect = "Allow"
        Action = [
          "iam:GetPolicy",
          "iam:GetPolicyVersion",
          "iam:ListPolicyVersions",
          "iam:CreatePolicyVersion",
          "iam:DeletePolicyVersion",
          "iam:AttachUserPolicy",
          "iam:DetachUserPolicy",
          "iam:ListAttachedUserPolicies",
        ]
        Resource = [
          "arn:aws:iam::827709230476:policy/fish-gl-engine-terraform-*",
          "arn:aws:iam::827709230476:user/fish-gl-engine-terraform",
        ]
      },
    ]
  })
}

resource "aws_iam_user_policy_attachment" "fish_gl_engine_terraform_refresh_reads" {
  user       = "fish-gl-engine-terraform"
  policy_arn = aws_iam_policy.fish_gl_engine_terraform_refresh_reads.arn
}
