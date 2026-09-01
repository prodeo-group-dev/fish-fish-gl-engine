# IM's service-to-service credential to call GL's own API
# (IM_GL_ENGINE_BEARER_TOKEN) - mirrors sop_service_account.tf exactly,
# built from the start (not deferred the way POP's own equivalent
# still is) since SOP's Cognito-service-account approach is now the
# proven pattern for this shape of caller, not something to re-derive.
#
# A dedicated Cognito app client + user, not SOP's - GL's own Auth.kt
# only accepts a single audience per JWTVerifier (`buildJwksVerifier`'s
# own KDoc explains why `withAudience(vararg)` doesn't work for
# "accept either of two"), so each service caller needs its own
# app client (its own `aud` claim) and its own named Ktor auth
# provider (`FISH_JWT_SERVICE_AUTH_NAME_IM`, `installFishJwtAuth`).

resource "random_password" "im_service_account" {
  length  = 32
  special = true
  # Same Cognito-accepted-symbol-set override as sop_service_account.tf's
  # own random_password.sop_service_account.
  override_special = "!@#$%^&*()-_=+"
}

resource "aws_secretsmanager_secret" "im_service_account_password" {
  name        = "${var.project_name}/${var.environment}/im-service-account-password"
  description = "Cognito password for IM's GL Engine service account (IM_GL_ENGINE_SERVICE_ACCOUNT_PASSWORD)"
}

resource "aws_secretsmanager_secret_version" "im_service_account_password" {
  secret_id     = aws_secretsmanager_secret.im_service_account_password.id
  secret_string = random_password.im_service_account.result
}

resource "aws_secretsmanager_secret" "im_service_account_client_secret" {
  name        = "${var.project_name}/${var.environment}/im-service-account-client-secret"
  description = "Cognito app client secret for IM's GL Engine service account (IM_GL_ENGINE_SERVICE_ACCOUNT_CLIENT_SECRET)"
}

resource "aws_secretsmanager_secret_version" "im_service_account_client_secret" {
  secret_id     = aws_secretsmanager_secret.im_service_account_client_secret.id
  secret_string = aws_cognito_user_pool_client.im_service.client_secret
}

resource "aws_cognito_user_pool_client" "im_service" {
  name         = "${var.project_name}-im-service"
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

resource "aws_cognito_user" "im_service" {
  user_pool_id   = aws_cognito_user_pool.this.id
  username       = var.im_service_account_email
  password       = random_password.im_service_account.result
  message_action = "SUPPRESS"

  attributes = {
    email          = var.im_service_account_email
    email_verified = "true"
  }
}
