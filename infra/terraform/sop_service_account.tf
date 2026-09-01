# SOP's service-to-service credential to call GL's own API
# (SOP_GL_ENGINE_BEARER_TOKEN, deliberately left unset in sop.tf's own
# task definition until this was decided - see sop.tf's own file
# header and SOP's Application.kt's glBearerTokenProvider lambda).
#
# Resolved 2026-09-01, confirmed with the user before building: a
# dedicated Cognito service-account user, not a client-credentials
# (M2M) app client. Auth.kt verifies Cognito ID tokens specifically -
# it checks the `aud` claim and the `email` claim (cognito.tf's own
# comment on this). Cognito's client_credentials grant only ever
# issues an access token, which carries `client_id` instead of `aud`
# and has no `email` claim at all by default - accepting it would mean
# extending Auth.kt (every route's shared auth module) with a whole
# second identity concept (a "service principal" distinct from
# User/Membership) just for this one caller. The service-account
# route needs none of that: SOP logs in via Cognito's InitiateAuth
# (USER_PASSWORD_AUTH) as a real Cognito user, gets back an ordinary
# ID token, and is indistinguishable from any other authenticated
# caller as far as Auth.kt is concerned. The matching real User +
# Membership row lives in V17__sop_service_account.sql, not here -
# that's application data, not infrastructure.

resource "random_password" "sop_service_account" {
  length  = 32
  special = true
  # Cognito's own password-policy charset is narrower than
  # random_password's full special-character default set (cognito.tf's
  # aws_cognito_user_pool.password_policy requires upper/lower/number/
  # symbol but Cognito rejects a handful of characters outside its own
  # allowed symbol set) - override_special keeps this to symbols
  # Cognito is confirmed to accept.
  override_special = "!@#$%^&*()-_=+"
}

resource "aws_secretsmanager_secret" "sop_service_account_password" {
  name        = "${var.project_name}/${var.environment}/sop-service-account-password"
  description = "Cognito password for SOP's GL Engine service account (SOP_GL_ENGINE_SERVICE_ACCOUNT_PASSWORD)"
}

resource "aws_secretsmanager_secret_version" "sop_service_account_password" {
  secret_id     = aws_secretsmanager_secret.sop_service_account_password.id
  secret_string = random_password.sop_service_account.result
}

resource "aws_secretsmanager_secret" "sop_service_account_client_secret" {
  name        = "${var.project_name}/${var.environment}/sop-service-account-client-secret"
  description = "Cognito app client secret for SOP's GL Engine service account (SOP_GL_ENGINE_SERVICE_ACCOUNT_CLIENT_SECRET)"
}

resource "aws_secretsmanager_secret_version" "sop_service_account_client_secret" {
  secret_id     = aws_secretsmanager_secret.sop_service_account_client_secret.id
  secret_string = aws_cognito_user_pool_client.sop_service.client_secret
}

# A dedicated app client, not the existing "web" client
# (cognito.tf) - USER_PASSWORD_AUTH (plain password over TLS, not SRP)
# is an acceptable tradeoff for a server-to-server caller (no browser
# eavesdropping surface, and implementing SRP client-side in Kotlin is
# real cryptographic work this doesn't need), but keeping it on its
# own client means the browser-facing "web" client's SRP-only,
# no-client-secret posture (cognito.tf's own comment on why) stays
# untouched. generate_secret = true here, unlike "web" - this client
# is never embedded in a browser, so a client secret is real
# defense-in-depth: SECRET_HASH is required on every InitiateAuth
# call, so the password alone isn't sufficient if it ever leaks
# without the secret too.
resource "aws_cognito_user_pool_client" "sop_service" {
  name         = "${var.project_name}-sop-service"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret = true

  explicit_auth_flows = [
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

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

# The actual Cognito identity SOP authenticates as. `password` (not
# `temporary_password`) sets a PERMANENT password directly via
# AdminSetUserPassword - no forced-change-on-first-login flow, since
# there's no interactive session to ever prompt one. message_action =
# SUPPRESS - no welcome email to a mailbox nobody reads.
resource "aws_cognito_user" "sop_service" {
  user_pool_id   = aws_cognito_user_pool.this.id
  username       = var.sop_service_account_email
  password       = random_password.sop_service_account.result
  message_action = "SUPPRESS"

  attributes = {
    email          = var.sop_service_account_email
    email_verified = "true"
  }
}
