# Amazon Cognito as the external IdP for Auth.kt's existing JWT verifier
# (FISH_JWT_ISSUER/AUDIENCE/JWKS_URL) - closes the "no real IdP to point
# at" gap flagged since the CD pipeline first went live
# (docs/GL_Production_Readiness_Plan.md).
#
# User pool only, no identity pool - the mobile-first PWA frontend only
# ever calls GL's own API, never AWS resources directly, so there's no
# need to exchange a Cognito token for temporary AWS credentials
# (aws-auth skill's own "User Pool vs Identity Pool" guidance).
#
# No Hosted UI / OAuth redirect flow configured here - the frontend is
# expected to build its own sign-up/sign-in screens directly against
# Cognito's API (Amplify's Auth category, or the Cognito Identity
# Provider SDK - USER_SRP_AUTH), not redirect out to a generic
# Cognito-hosted page. Chosen because Prodeo Capital's product roadmap
# explicitly calls for "a gamified user experience" as a FiSH design
# objective - a full-page redirect away from the app doesn't fit that,
# and a custom onboarding flow needs full control over its own screens
# anyway. Revisit (and add a user pool domain + OAuth config) if that
# product direction changes.
#
# **Critical integration detail for whoever builds the frontend**:
# Auth.kt's verifier checks the standard `aud` claim and the `email`
# claim (see its own KDoc) to resolve identity. Cognito's ID token
# carries both; Cognito's ACCESS token carries neither (it has
# `client_id` instead of `aud`, and no `email` claim by default) - so
# the frontend MUST send the ID token as the API's bearer token, not
# the access token, or every call 401s against Auth.kt's existing logic
# unmodified.

resource "aws_cognito_user_pool" "this" {
  name = "${var.project_name}-${var.environment}"

  # Sign in with email, not a separate username - matches User.email
  # already being documented as the login identifier (docs/DDD_Design.md
  # Section 10.3) and Auth.kt's own identity-resolution join key
  # (email claim -> User.email -> User).
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length    = 8
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = true
  }

  # OPTIONAL, not REQUIRED - Tenant already has its own KYB/admin-KYC
  # identity verification layer (domain.tenancy.Tenant.kybStatus /
  # adminKycStatus). Cognito MFA is a complementary login-security
  # control, not the app's only identity check, so it doesn't need to
  # be forced on every sign-up on top of KYC (progressive/forgiving
  # onboarding is an already-established UX principle for this
  # project - see feedback_non_accountant_ux).
  mfa_configuration = "OPTIONAL"
  software_token_mfa_configuration {
    enabled = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  admin_create_user_config {
    allow_admin_create_user_only = false
  }

  # Replaces the default COGNITO_DEFAULT sender (rate-limited, not
  # meant for production use - confirmed 2026-08-27 as the real reason
  # verification codes weren't reliably arriving) with SES, via the
  # domain identity notifications.tf provisions. Two-phase-apply
  # dependency, same shape as acm.tf: aws_ses_domain_identity must
  # actually be verified (the TXT record added at the external DNS
  # provider) before Cognito will accept this source_arn.
  email_configuration {
    email_sending_account = "DEVELOPER"
    source_arn            = aws_ses_domain_identity.this.arn
    from_email_address    = "FiSH <noreply@${aws_ses_domain_identity.this.domain}>"
  }

  # SMS verification for the admin phone number requirement
  # (tenant.kt's adminPhoneVerificationStatus, added 2026-08-27) - the
  # role Cognito assumes to actually send the code via SNS.
  sms_configuration {
    external_id    = "${var.project_name}-${var.environment}-cognito-sms"
    sns_caller_arn = aws_iam_role.cognito_sms.arn
  }

  tags = {
    Project = var.project_name
  }
}

resource "aws_cognito_user_pool_client" "web" {
  name         = "${var.project_name}-web"
  user_pool_id = aws_cognito_user_pool.this.id

  # No client secret - a browser-based PWA is a public client and
  # cannot protect a secret (aws-auth skill: "App client secret + SPA =
  # broken auth" - token calls fail unless a SECRET_HASH is sent, which
  # a browser can't do safely). generate_secret defaults to false;
  # left explicit here since it's the one setting that would silently
  # break auth if it were ever flipped.
  generate_secret = false

  # SRP (Secure Remote Password) - the password itself never crosses
  # the wire, unlike ALLOW_USER_PASSWORD_AUTH's plaintext flow. Plus
  # refresh-token support so a session survives past the access/ID
  # token's short lifetime without re-prompting for a password.
  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

  # Doesn't reveal whether a given email is a registered account -
  # standard enumeration-prevention setting the aws-auth skill itself
  # calls out.
  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 30

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

# Derived, not hand-constructed from region + pool id - `endpoint` is
# the provider's own computed attribute
# (cognito-idp.<region>.amazonaws.com/<pool-id>), so there's no risk of
# this drifting out of sync with the real issuer URL Cognito actually
# signs tokens with.
locals {
  fish_jwt_issuer   = "https://${aws_cognito_user_pool.this.endpoint}"
  fish_jwt_audience = aws_cognito_user_pool_client.web.id
  fish_jwt_jwks_url = "https://${aws_cognito_user_pool.this.endpoint}/.well-known/jwks.json"
}
