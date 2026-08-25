# Only the DB password goes through Secrets Manager - it's the one
# genuinely sensitive value among FISH_DB_*/FISH_JWT_* (host/port/name/
# user are configuration, not credentials; the JWT settings are a
# public issuer/audience/JWKS URL, not a secret - the JWKS endpoint is
# fetched in-process specifically because it's public key material,
# docs/DDD_Design.md Section 10.19). Everything else is a plain
# environment variable on the task definition (ecs.tf).

resource "aws_secretsmanager_secret" "db_password" {
  name        = "${var.project_name}/${var.environment}/db-password"
  description = "FISH_DB_PASSWORD for ${var.project_name} (${var.environment})"
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = var.db_password
}
