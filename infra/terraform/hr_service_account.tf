# HR's service-to-service credential to call GL's own API
# (HR_GL_ENGINE_SERVICE_ACCOUNT_*) - mirrors im_service_account.tf/
# sop_service_account.tf exactly, 2026-09-02 ("Scope and build HR's
# HTTP layer") - the proven pattern for this shape of caller.
#
# A dedicated Cognito app client + user, not IM's/SOP's - GL's own
# Auth.kt only accepts a single audience per JWTVerifier
# (`buildJwksVerifier`'s own KDoc explains why `withAudience(vararg)`
# doesn't work for "accept either of two"), so each service caller
# needs its own app client (its own `aud` claim) and its own named
# Ktor auth provider (`FISH_JWT_SERVICE_AUTH_NAME_HR`,
# `installFishJwtAuth`).

resource "random_password" "hr_service_account" {
  length  = 32
  special = true
  # Same Cognito-accepted-symbol-set override as im_service_account.tf's
  # own random_password.im_service_account.
  override_special = "!@#$%^&*()-_=+"
}

resource "aws_secretsmanager_secret" "hr_service_account_password" {
  name        = "${var.project_name}/${var.environment}/hr-service-account-password"
  description = "Cognito password for HR's GL Engine service account (HR_GL_ENGINE_SERVICE_ACCOUNT_PASSWORD)"
}

resource "aws_secretsmanager_secret_version" "hr_service_account_password" {
  secret_id     = aws_secretsmanager_secret.hr_service_account_password.id
  secret_string = random_password.hr_service_account.result
}

resource "aws_secretsmanager_secret" "hr_service_account_client_secret" {
  name        = "${var.project_name}/${var.environment}/hr-service-account-client-secret"
  description = "Cognito app client secret for HR's GL Engine service account (HR_GL_ENGINE_SERVICE_ACCOUNT_CLIENT_SECRET)"
}

resource "aws_secretsmanager_secret_version" "hr_service_account_client_secret" {
  secret_id     = aws_secretsmanager_secret.hr_service_account_client_secret.id
  secret_string = aws_cognito_user_pool_client.hr_service.client_secret
}

resource "aws_cognito_user_pool_client" "hr_service" {
  name         = "${var.project_name}-hr-service"
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

resource "aws_cognito_user" "hr_service" {
  user_pool_id   = aws_cognito_user_pool.this.id
  username       = var.hr_service_account_email
  password       = random_password.hr_service_account.result
  message_action = "SUPPRESS"

  attributes = {
    email          = var.hr_service_account_email
    email_verified = "true"
  }
}
