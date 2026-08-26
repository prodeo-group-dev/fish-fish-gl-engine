# Only the DB password goes through Secrets Manager - it's the one
# genuinely sensitive value among FISH_DB_*/FISH_JWT_* (host/port/name/
# user are configuration, not credentials; the JWT settings are a
# public issuer/audience/JWKS URL, not a secret - the JWKS endpoint is
# fetched in-process specifically because it's public key material,
# docs/DDD_Design.md Section 10.19). Everything else is a plain
# environment variable on the task definition (ecs.tf).
#
# The password itself comes from random_password.db (rds.tf), the same
# value used as the RDS master password - generated once by Terraform,
# never typed into a -var flag or .tfvars file, closing the credential-
# hygiene gap the old required `db_password` variable had.

resource "aws_secretsmanager_secret" "db_password" {
  name        = "${var.project_name}/${var.environment}/db-password"
  description = "FISH_DB_PASSWORD for ${var.project_name} (${var.environment})"
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db.result
}
