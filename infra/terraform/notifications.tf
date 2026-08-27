# Real, production-grade delivery for both of Cognito's verification
# codes - email (SES, replacing the rate-limited COGNITO_DEFAULT sender)
# and SMS (SNS, for the admin phone number requirement added to KYB
# 2026-08-27 - see tenant.kt's own KDoc). Both codes are already genuine
# random 6-digit numbers by default; nothing about that is configurable
# or needed building - the actual, real gap was always delivery.

# --- Email (SES) -----------------------------------------------------

# A subdomain, not theprodeogroup.com itself - avoids any risk of this
# identity's own DNS records (verification TXT, DKIM CNAMEs) colliding
# with whatever's already there for the root domain (the ALB/CloudFront
# CNAMEs, any existing mail setup). Cognito's "From" address becomes
# noreply@mail.theprodeogroup.com.
resource "aws_ses_domain_identity" "this" {
  domain = "mail.${var.root_domain}"
}

# Easy DKIM - the recommended default over the older, more manual
# "custom DKIM" setup. Produces 3 CNAME records (outputs.tf) that must
# be added at the external DNS provider before ses_domain_identity
# reaches "verified", same two-phase-apply shape as acm.tf.
resource "aws_ses_domain_dkim" "this" {
  domain = aws_ses_domain_identity.this.domain
}

# --- SMS (SNS, for Cognito's phone_number verification) ---------------

# Cognito assumes this role to call sns:Publish when it sends an SMS
# verification code - the SMS equivalent of SES's source_arn above,
# except SNS access genuinely does need an IAM role (SES's same-account
# same-region case doesn't).
resource "aws_iam_role" "cognito_sms" {
  name = "${var.project_name}-cognito-sms"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "cognito-idp.amazonaws.com" }
        Action    = "sts:AssumeRole"
        Condition = {
          StringEquals = {
            "sts:ExternalId" = "${var.project_name}-${var.environment}-cognito-sms"
          }
        }
      }
    ]
  })

  tags = {
    Project = var.project_name
  }
}

resource "aws_iam_role_policy" "cognito_sms_publish" {
  name = "${var.project_name}-cognito-sms-publish"
  role = aws_iam_role.cognito_sms.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "sns:Publish"
        Resource = "*"
      }
    ]
  })
}

# SNS's own per-account SMS spending limit - would raise AWS's small
# default monthly cap to a still-modest but usable starting point, but
# left OUT of this apply deliberately: `SetSMSAttributes` failed with
# "The AWS Access Key Id needs a subscription for the service
# (PinpointSmsVoiceV2)" (2026-08-27) - a genuine account-level gate
# (this account has never used SMS/Pinpoint messaging before), not an
# IAM permission gap this policy can fix. Needs a one-time activation
# via the SNS console's "Text messaging (SMS)" page (or an AWS Support
# case if that doesn't self-resolve) before this resource can apply.
# aws_iam_role.cognito_sms/aws_cognito_user_pool.this's sms_configuration
# stay wired either way - once the account's unblocked, actual SMS
# sending just starts working without touching this file again; only
# this specific spending-limit override is deferred.
#
# resource "aws_sns_sms_preferences" "this" {
#   monthly_spend_limit = 25
#   default_sms_type    = "Transactional"
# }

# --- Runtime access for the GL API itself ------------------------------

# The ECS task role (iam.tf), not the Terraform-operator user above -
# this is what the running application needs at request time to check
# whether Cognito actually verified a caller's phone_number before
# GL's own RecordAdminPhoneNumberUseCase accepts it (never trusting the
# client's own claim that Cognito verified something - see that use
# case's own KDoc). Scoped to this specific user pool, read-only.
resource "aws_iam_role_policy" "ecs_task_cognito_read" {
  name = "${var.project_name}-cognito-read"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "cognito-idp:AdminGetUser"
        Resource = aws_cognito_user_pool.this.arn
      }
    ]
  })
}
