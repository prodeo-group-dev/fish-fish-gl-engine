# SOP's service-to-service credential to call IM's own API
# (SOP_IM_SERVICE_ACCOUNT_*) - mirrors pop_service_account.tf exactly
# (2026-09-01, "Wire SOP's issue-side linkage to IM"). Lives in GL's
# own Terraform, not IM's or SOP's, because that's where the shared
# Cognito user pool IM's own inbound auth trusts already lives - same
# reasoning as im_service_account.tf/sop_service_account.tf/
# pop_service_account.tf, which also provision their app clients here
# despite authenticating *to* other services, not calling GL itself.
#
# A dedicated Cognito app client + user, distinct from SOP's own
# GL-facing service account (sop_service_account.tf) - a different
# audience entirely. IM's `Auth.kt` needs a third named auth provider
# to trust it (`IM_JWT_SERVICE_AUTH_NAME_SOP`), alongside the existing
# human and POP-facing ones.

resource "random_password" "sop_im_service_account" {
  length           = 32
  special          = true
  override_special = "!@#$%^&*()-_=+"
}

resource "aws_secretsmanager_secret" "sop_im_service_account_password" {
  name        = "${var.project_name}/${var.environment}/sop-im-service-account-password"
  description = "Cognito password for SOP's IM service account (SOP_IM_SERVICE_ACCOUNT_PASSWORD)"
}

resource "aws_secretsmanager_secret_version" "sop_im_service_account_password" {
  secret_id     = aws_secretsmanager_secret.sop_im_service_account_password.id
  secret_string = random_password.sop_im_service_account.result
}

resource "aws_secretsmanager_secret" "sop_im_service_account_client_secret" {
  name        = "${var.project_name}/${var.environment}/sop-im-service-account-client-secret"
  description = "Cognito app client secret for SOP's IM service account (SOP_IM_SERVICE_ACCOUNT_CLIENT_SECRET)"
}

resource "aws_secretsmanager_secret_version" "sop_im_service_account_client_secret" {
  secret_id     = aws_secretsmanager_secret.sop_im_service_account_client_secret.id
  secret_string = aws_cognito_user_pool_client.sop_im_service.client_secret
}

resource "aws_cognito_user_pool_client" "sop_im_service" {
  name         = "${var.project_name}-sop-im-service"
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

resource "aws_cognito_user" "sop_im_service" {
  user_pool_id   = aws_cognito_user_pool.this.id
  username       = var.sop_im_service_account_email
  password       = random_password.sop_im_service_account.result
  message_action = "SUPPRESS"

  attributes = {
    email          = var.sop_im_service_account_email
    email_verified = "true"
  }
}
