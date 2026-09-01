# POP's service-to-service credential to call IM's own API
# (POP_IM_SERVICE_ACCOUNT_*) - mirrors im_service_account.tf exactly
# (2026-09-01, "scope out how POP's receive-line would call IM"). Lives
# in GL's own Terraform, not IM's or POP's, because that's where the
# shared Cognito user pool IM's own inbound auth trusts already lives -
# same reasoning as im_service_account.tf/sop_service_account.tf, which
# also provision their app clients here despite authenticating *to* GL,
# not calling it.
#
# A dedicated Cognito app client + user, not IM's own or SOP's - IM's
# `Auth.kt` (like GL's) only accepts a single audience per `JWTVerifier`,
# so this caller needs its own app client (its own `aud` claim) and IM
# needs a new named auth provider to trust it
# (`IM_JWT_SERVICE_AUTH_NAME_POP`).

resource "random_password" "pop_im_service_account" {
  length  = 32
  special = true
  # Same Cognito-accepted-symbol-set override as every other service
  # account's own random_password in this file set.
  override_special = "!@#$%^&*()-_=+"
}

resource "aws_secretsmanager_secret" "pop_im_service_account_password" {
  name        = "${var.project_name}/${var.environment}/pop-im-service-account-password"
  description = "Cognito password for POP's IM service account (POP_IM_SERVICE_ACCOUNT_PASSWORD)"
}

resource "aws_secretsmanager_secret_version" "pop_im_service_account_password" {
  secret_id     = aws_secretsmanager_secret.pop_im_service_account_password.id
  secret_string = random_password.pop_im_service_account.result
}

resource "aws_secretsmanager_secret" "pop_im_service_account_client_secret" {
  name        = "${var.project_name}/${var.environment}/pop-im-service-account-client-secret"
  description = "Cognito app client secret for POP's IM service account (POP_IM_SERVICE_ACCOUNT_CLIENT_SECRET)"
}

resource "aws_secretsmanager_secret_version" "pop_im_service_account_client_secret" {
  secret_id     = aws_secretsmanager_secret.pop_im_service_account_client_secret.id
  secret_string = aws_cognito_user_pool_client.pop_im_service.client_secret
}

resource "aws_cognito_user_pool_client" "pop_im_service" {
  name         = "${var.project_name}-pop-im-service"
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

resource "aws_cognito_user" "pop_im_service" {
  user_pool_id   = aws_cognito_user_pool.this.id
  username       = var.pop_im_service_account_email
  password       = random_password.pop_im_service_account.result
  message_action = "SUPPRESS"

  attributes = {
    email          = var.pop_im_service_account_email
    email_verified = "true"
  }
}
