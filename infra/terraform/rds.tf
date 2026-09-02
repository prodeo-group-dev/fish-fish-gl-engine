# Provisions the Postgres instance this service connects to - closes
# docs/GL_Production_Readiness_Plan.md's last open item for Phase 1b
# (previously deliberately out of scope, "point db_host/db_password at
# wherever Postgres actually ends up running - an RDS instance
# provisioned separately, or anywhere else").
#
# Same VPC/subnet tradeoff already made for ECS (network.tf) - the
# default VPC's public subnets, no NAT Gateway, locked down at the
# security-group layer rather than network-isolated. `publicly_
# accessible = false` below means no public IP either way, but this
# is still not the private-subnet-plus-NAT-Gateway shape a hardened
# production network review would want. Same flag, not a new one.

resource "random_password" "db" {
  length  = 32
  special = false # RDS master passwords reject some special characters outright; alphanumeric is simpler and just as strong at this length
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.project_name}-${var.environment}"
  subnet_ids = data.aws_subnets.default.ids

  tags = {
    Project = var.project_name
  }
}

resource "aws_security_group" "rds" {
  name        = "${var.project_name}-rds"
  description = "Allow inbound Postgres only from the ECS service"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Postgres from the ECS service only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.service.id]
  }

  # POP (pop.tf, 2026-08-30) shares this same RDS instance (a new
  # database on it, not a second instance - RDS Free Tier's hours are
  # account-wide, a second always-on instance would exceed them
  # outright) - its own ECS service needs the same DB-layer access GL's
  # already has.
  ingress {
    description     = "Postgres from the POP ECS service"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.pop_service.id]
  }

  # SOP (sop.tf, 2026-08-31) shares this same RDS instance too, same
  # reasoning as POP's own ingress rule above - a new database
  # (sop_production), not a second instance.
  ingress {
    description     = "Postgres from the SOP ECS service"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.sop_service.id]
  }

  # IM (im.tf, 2026-09-01) shares this same RDS instance too, same
  # reasoning as POP/SOP's own ingress rules above - a new database
  # (im_production), not a second instance. Missed on the first apply -
  # the app crash-looped with a Postgres connect timeout until this was
  # added.
  ingress {
    description     = "Postgres from the IM ECS service"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.im_service.id]
  }

  # HR (hr.tf, 2026-09-02) shares this same RDS instance too, same
  # reasoning as POP/SOP/IM's own ingress rules above - a new database
  # (hr_production), not a second instance. Same gap as IM's own first
  # apply - added proactively here rather than waiting to rediscover it
  # via another crash-looped connect timeout.
  ingress {
    description     = "Postgres from the HR ECS service"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.hr_service.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Project = var.project_name
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.project_name}-${var.environment}"
  engine         = "postgres"
  engine_version = var.db_engine_version

  instance_class    = var.db_instance_class
  allocated_storage = var.db_allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true # effectively free, no reason not to

  db_name  = var.db_name
  username = var.db_user
  password = random_password.db.result
  port     = tonumber(var.db_port)

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false

  multi_az = false # matches ECS's own desired_count=1 no-HA baseline - a real availability gap, not an oversight, same category as network.tf's own NAT Gateway tradeoff

  backup_retention_period = var.db_backup_retention_days
  # skip_final_snapshot = true is only correct while this instance holds
  # no real data (still true as of first apply, 2026-08-26 - the ECS
  # service has never successfully run a real image against it yet).
  # Flip to false, and set deletion_protection = true, the moment real
  # data exists here - not automatically revisited by this comment.
  skip_final_snapshot = true
  deletion_protection = false

  tags = {
    Project = var.project_name
  }
}
