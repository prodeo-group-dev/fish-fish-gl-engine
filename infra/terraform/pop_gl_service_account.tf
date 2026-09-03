# POP's service-to-service credential to call GL's own API
# (POP_GL_ENGINE_SERVICE_ACCOUNT_*) - mirrors hr_service_account.tf/
# im_service_account.tf exactly (docs/POP_GL_Service_Account_Closure_Plan.md).
# Closes the gap flagged in pop.tf's own file header: POP_GL_ENGINE_BEARER_TOKEN
# was deliberately deferred when POP's Order Fulfillment backend shipped,
# and was never actually wired to anything - every /purchase-orders/{id}/match
# and /purchase-orders/{id}/pay call throws before this exists.
#
# A dedicated Cognito app client + user, not IM's/SOP's/HR's - GL's own
# Auth.kt only accepts a single audience per JWTVerifier
# (`buildJwksVerifier`'s own KDoc explains why `withAudience(vararg)`
# doesn't work for "accept either of two"), so each service caller
# needs its own app client (its own `aud` claim) and its own named
# Ktor auth provider (`FISH_JWT_SERVICE_AUTH_NAME_POP`,
# `installFishJwtAuth`).

resource "random_password" "pop_gl_service_account" {
  length  = 32
  special = true
  # Same Cognito-accepted-symbol-set override as every other service
  # account's own random_password in this file set.
  override_special = "!@#$%^&*()-_=+"
}

resource "aws_secretsmanager_secret" "pop_gl_service_account_password" {
  name        = "${var.project_name}/${var.environment}/pop-gl-service-account-password"
  description = "Cognito password for POP's GL Engine service account (POP_GL_ENGINE_SERVICE_ACCOUNT_PASSWORD)"
}

resource "aws_secretsmanager_secret_version" "pop_gl_service_account_password" {
  secret_id     = aws_secretsmanager_secret.pop_gl_service_account_password.id
  secret_string = random_password.pop_gl_service_account.result
}

resource "aws_secretsmanager_secret" "pop_gl_service_account_client_secret" {
  name        = "${var.project_name}/${var.environment}/pop-gl-service-account-client-secret"
  description = "Cognito app client secret for POP's GL Engine service account (POP_GL_ENGINE_SERVICE_ACCOUNT_CLIENT_SECRET)"
}

resource "aws_secretsmanager_secret_version" "pop_gl_service_account_client_secret" {
  secret_id     = aws_secretsmanager_secret.pop_gl_service_account_client_secret.id
  secret_string = aws_cognito_user_pool_client.pop_gl_service.client_secret
}

resource "aws_cognito_user_pool_client" "pop_gl_service" {
  name         = "${var.project_name}-pop-gl-service"
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

resource "aws_cognito_user" "pop_gl_service" {
  user_pool_id   = aws_cognito_user_pool.this.id
  username       = var.pop_gl_service_account_email
  password       = random_password.pop_gl_service_account.result
  message_action = "SUPPRESS"

  attributes = {
    email          = var.pop_gl_service_account_email
    email_verified = "true"
  }
}
