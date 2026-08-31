# SOP (Sales Order Processing) - built with a real HTTP server
# (2026-08-30) and, as of 2026-08-31, real Customer persistence (its
# own database, provisioned manually the same way POP's was), but had
# zero AWS infrastructure until now.
#
# Deliberately reuses GL's VPC (network.tf), ECS cluster (ecs.tf), ALB
# (alb.tf), and Cognito pool (cognito.tf) - same sharing rationale as
# pop.tf's own file header. Dedicated resources only where sharing
# would be wrong: IAM roles, the database itself (new logical database
# on GL's shared RDS instance, own credentials - sop_production/sop_app,
# provisioned manually 2026-08-31, same "second RDS instance would
# exceed Free Tier hours" reasoning as POP's), the OIDC deploy role,
# and the ACM certificate.
#
# SOP_GL_ENGINE_BEARER_TOKEN is deliberately NOT set below - it's a
# lambda in SOP's own Application.kt (`val glBearerTokenProvider = {
# ... }`), evaluated only when record-sale/record-collection are
# actually invoked, not at process startup (verified by reading the
# source before writing this - Application.kt lines 76-79). Every
# other route (health, customers) works immediately. How SOP
# authenticates to GL Engine is a deliberately deferred decision, same
# treatment POP's own bearer token got.
#
# SOP's own database password already exists in Secrets Manager
# (fish-sales-order-processing/production/db-password, created
# manually 2026-08-31 when the database itself was provisioned) - this
# file references it via a data source, it does NOT create a new
# secret or a new random_password the way pop.tf did for POP's, since
# the real credential (and the real ALTER DATABASE ... OWNER TO
# sop_app already applied) already exists and must not be rotated out
# from under a database that's already been provisioned against it.

# --- ECR ----------------------------------------------------------------

resource "aws_ecr_repository" "sop" {
  name                 = "fish-sales-order-processing"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project = "fish-sales-order-processing"
  }
}

resource "aws_ecr_lifecycle_policy" "sop" {
  repository = aws_ecr_repository.sop.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 7 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the last 20 tagged images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["sha-"]
          countType     = "imageCountMoreThan"
          countNumber   = 20
        }
        action = { type = "expire" }
      }
    ]
  })
}

# --- ACM (separate from GL's and POP's own certificates) ----------------

resource "aws_acm_certificate" "sop" {
  domain_name       = var.sop_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project = "fish-sales-order-processing"
  }
}

resource "aws_acm_certificate_validation" "sop" {
  certificate_arn = aws_acm_certificate.sop.arn
}

# --- IAM: ECS task execution/task roles (dedicated, not shared) --------

resource "aws_iam_role" "sop_ecs_task_execution" {
  name = "fish-sop-ecs-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "sop_ecs_task_execution_managed" {
  role       = aws_iam_role.sop_ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "sop_ecs_task_execution_secrets" {
  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [data.aws_secretsmanager_secret.sop_db_password.arn]
  }
}

resource "aws_iam_role_policy" "sop_ecs_task_execution_secrets" {
  name   = "fish-sop-read-db-secret"
  role   = aws_iam_role.sop_ecs_task_execution.id
  policy = data.aws_iam_policy_document.sop_ecs_task_execution_secrets.json
}

# Empty, same reasoning as GL's/POP's own aws_iam_role.ecs_task - SOP's
# application code makes no AWS SDK calls of its own (Postgres and the
# GL Engine's HTTP API, neither via the AWS SDK).
resource "aws_iam_role" "sop_ecs_task" {
  name = "fish-sop-ecs-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# --- IAM: OIDC deploy role for SOP's own GitHub repo --------------------

data "aws_iam_policy_document" "sop_github_actions_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [var.sop_github_oidc_subject]
    }
  }
}

resource "aws_iam_role" "sop_github_actions_deploy" {
  name               = "fish-sop-github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.sop_github_actions_assume_role.json
}

data "aws_iam_policy_document" "sop_github_actions_deploy" {
  statement {
    sid       = "PushToEcr"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # GetAuthorizationToken doesn't support resource-level scoping
  }

  statement {
    sid    = "PushToThisRepoOnly"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:PutImage",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:BatchGetImage"
    ]
    resources = [aws_ecr_repository.sop.arn]
  }

  statement {
    sid    = "DeployToEcs"
    effect = "Allow"
    actions = [
      "ecs:RegisterTaskDefinition",
      "ecs:DescribeTaskDefinition",
      "ecs:UpdateService",
      "ecs:DescribeServices"
    ]
    resources = ["*"] # ECS doesn't support resource-level scoping on these either
  }

  statement {
    sid     = "PassTaskRoles"
    effect  = "Allow"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.sop_ecs_task_execution.arn,
      aws_iam_role.sop_ecs_task.arn
    ]
  }
}

resource "aws_iam_role_policy" "sop_github_actions_deploy" {
  name   = "fish-sop-deploy"
  role   = aws_iam_role.sop_github_actions_deploy.id
  policy = data.aws_iam_policy_document.sop_github_actions_deploy.json
}

# --- Secrets Manager: reference the already-provisioned secret ----------

data "aws_secretsmanager_secret" "sop_db_password" {
  name = "fish-sales-order-processing/production/db-password"
}

# --- CloudWatch -----------------------------------------------------------

resource "aws_cloudwatch_log_group" "sop" {
  name              = "/ecs/fish-sales-order-processing"
  retention_in_days = 30

  tags = {
    Project = "fish-sales-order-processing"
  }
}

# --- ALB: shared load balancer, new target group + host-based rule ------

resource "aws_lb_target_group" "sop" {
  name        = var.sop_short_name
  port        = 8082
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "ip"

  health_check {
    path                = "/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }

  tags = {
    Project = "fish-sales-order-processing"
  }
}

# GL's own HTTPS listener (alb.tf) keeps its bare default_action as the
# catch-all for capital.theprodeogroup.com - this rule only intercepts
# requests for SOP's own subdomain, evaluated before the default.
# Priority 101 - POP's rule (pop.tf) already claims 100.
resource "aws_lb_listener_rule" "sop" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 101

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.sop.arn
  }

  condition {
    host_header {
      values = [var.sop_domain_name]
    }
  }

  # The rule itself doesn't need SOP's own certificate - SNI dispatch on
  # the shared listener still needs the cert attached to the listener,
  # so the listener also gets aws_lb_listener_certificate below.
}

resource "aws_lb_listener_certificate" "sop" {
  listener_arn    = aws_lb_listener.https.arn
  certificate_arn = aws_acm_certificate_validation.sop.certificate_arn
}

# --- Security group: dedicated, not reused from GL or POP --------------
#
# GL's own aws_security_group.service (network.tf) has its ingress rule
# hardcoded to var.container_port (GL's 8080); POP's is hardcoded to
# 8081. Reusing either verbatim would not actually open SOP's own port
# 8082 to the ALB - same shape, different port. This is also the exact
# bug pop.tf's own aws_ecs_service.pop originally shipped with
# (referencing aws_security_group.service.id instead of its own
# dedicated one) - not repeating it here.

resource "aws_security_group" "sop_service" {
  name        = "fish-sop-service"
  description = "Allow inbound only from the ALB, on the SOP container port"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB only"
    from_port       = 8082
    to_port         = 8082
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Project = "fish-sales-order-processing"
  }
}

# --- ECS: shared cluster, dedicated task definition + service -----------

resource "aws_ecs_task_definition" "sop" {
  family                   = "fish-sales-order-processing"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256 # smallest Fargate size to start - same "baseline, not capacity-planned" reasoning as GL's/POP's own task definitions
  memory                   = 512
  execution_role_arn       = aws_iam_role.sop_ecs_task_execution.arn
  task_role_arn            = aws_iam_role.sop_ecs_task.arn

  container_definitions = jsonencode([
    {
      # "bootstrap" - same placeholder-tag precedent as GL's/POP's own
      # task definitions: this revision can't actually start until the
      # first real manual deploy pushes a real image and registers a
      # new revision.
      name      = "fish-sales-order-processing"
      image     = "${aws_ecr_repository.sop.repository_url}:bootstrap"
      essential = true

      portMappings = [
        { containerPort = 8082, protocol = "tcp" }
      ]

      environment = [
        { name = "SOP_DB_HOST", value = aws_db_instance.this.address },
        { name = "SOP_DB_PORT", value = "5432" },
        { name = "SOP_DB_NAME", value = var.sop_db_name },
        { name = "SOP_DB_USER", value = var.sop_db_user },
        { name = "SOP_HTTP_PORT", value = "8082" },
        { name = "SOP_JWT_ISSUER", value = local.fish_jwt_issuer },
        { name = "SOP_JWT_AUDIENCE", value = local.fish_jwt_audience },
        { name = "SOP_JWT_JWKS_URL", value = local.fish_jwt_jwks_url },
        # /api suffix required - GL's own sales/record-sale and
        # sales/record-collection routes are mounted under
        # route("/api"), same precedent as POP's own base URL.
        { name = "SOP_GL_ENGINE_BASE_URL", value = "https://${var.domain_name}/api" },
        { name = "SOP_GL_ENGINE_TENANT_ID", value = var.sop_gl_engine_tenant_id },
        { name = "SOP_CORS_ALLOWED_ORIGIN", value = "https://${var.domain_name}" }
        # SOP_GL_ENGINE_BEARER_TOKEN deliberately omitted - see file header.
      ]

      secrets = [
        { name = "SOP_DB_PASSWORD", valueFrom = data.aws_secretsmanager_secret.sop_db_password.arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.sop.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])

  lifecycle {
    ignore_changes = [container_definitions]
  }

  tags = {
    Project = "fish-sales-order-processing"
  }
}

resource "aws_ecs_service" "sop" {
  name            = "fish-sales-order-processing-production"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.sop.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.sop_service.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.sop.arn
    container_name   = "fish-sales-order-processing"
    container_port   = 8082
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener_rule.sop]

  tags = {
    Project = "fish-sales-order-processing"
  }
}
