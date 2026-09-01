# IM (Inventory Management) - the parity audit's top remaining action
# item (2026-09-01, "Build IM's HTTP layer or stop implying 'Inventory
# Management' is real"). Built from scratch this session: application
# layer (Item/GoodsReceiptConfirmation/GoodsIssueConfirmation use
# cases), Exposed persistence (verified against a real Postgres),
# Ktor web layer with Cognito service-account auth to GL, and this
# infrastructure - IM had none of it before.
#
# Deliberately reuses GL's VPC (network.tf), ECS cluster (ecs.tf),
# ALB (alb.tf), Cognito pool (cognito.tf), and RDS instance (rds.tf) -
# same reasoning as pop.tf's own file header (free/shared resources
# stay shared; a second RDS instance would exceed this account's Free
# Tier hours outright). Dedicated resources only where sharing would be
# wrong: IAM roles, the database itself, the OIDC deploy role, and the
# ACM certificate - same four exceptions pop.tf already established.
#
# Unlike POP, IM's Cognito service account (im_service_account.tf) is
# built from day one, not deferred - SOP's own service-account pattern
# is now proven, not something to re-derive per-repo.

# --- ECR ----------------------------------------------------------------

resource "aws_ecr_repository" "im" {
  name                 = "fish-inventory-management"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project = "fish-inventory-management"
  }
}

resource "aws_ecr_lifecycle_policy" "im" {
  repository = aws_ecr_repository.im.name

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

# --- ACM (separate from GL's/POP's own certificates - see file header) --

resource "aws_acm_certificate" "im" {
  domain_name       = var.im_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project = "fish-inventory-management"
  }
}

resource "aws_acm_certificate_validation" "im" {
  certificate_arn = aws_acm_certificate.im.arn
}

# --- IAM: ECS task execution/task roles (dedicated, not shared) ---------

resource "aws_iam_role" "im_ecs_task_execution" {
  name = "fish-im-ecs-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "im_ecs_task_execution_managed" {
  role       = aws_iam_role.im_ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "im_ecs_task_execution_secrets" {
  statement {
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.im_db_password.arn,
      aws_secretsmanager_secret.im_service_account_password.arn,
      aws_secretsmanager_secret.im_service_account_client_secret.arn
    ]
  }
}

resource "aws_iam_role_policy" "im_ecs_task_execution_secrets" {
  name   = "fish-im-read-db-secret"
  role   = aws_iam_role.im_ecs_task_execution.id
  policy = data.aws_iam_policy_document.im_ecs_task_execution_secrets.json
}

# IM calls no AWS SDK service directly (Postgres, the GL Engine's own
# HTTP API) - this role exists only because ECS requires a task role
# distinct from the execution role, not because IM needs any
# permissions on it yet.
resource "aws_iam_role" "im_ecs_task" {
  name = "fish-im-ecs-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# --- IAM: OIDC deploy role for IM's own GitHub repo ----------------------

data "aws_iam_policy_document" "im_github_actions_assume_role" {
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
      values   = [var.im_github_oidc_subject]
    }
  }
}

resource "aws_iam_role" "im_github_actions_deploy" {
  name               = "fish-im-github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.im_github_actions_assume_role.json
}

data "aws_iam_policy_document" "im_github_actions_deploy" {
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
    resources = [aws_ecr_repository.im.arn]
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
      aws_iam_role.im_ecs_task_execution.arn,
      aws_iam_role.im_ecs_task.arn
    ]
  }
}

resource "aws_iam_role_policy" "im_github_actions_deploy" {
  name   = "fish-im-deploy"
  role   = aws_iam_role.im_github_actions_deploy.id
  policy = data.aws_iam_policy_document.im_github_actions_deploy.json
}

# --- Secrets Manager (IM's own DB password, not GL's/POP's/SOP's) -------

resource "random_password" "im_db" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "im_db_password" {
  name        = "fish-inventory-management/production/db-password"
  description = "IM_DB_PASSWORD"
}

resource "aws_secretsmanager_secret_version" "im_db_password" {
  secret_id     = aws_secretsmanager_secret.im_db_password.id
  secret_string = random_password.im_db.result
}

# --- CloudWatch -----------------------------------------------------------

resource "aws_cloudwatch_log_group" "im" {
  name              = "/ecs/fish-inventory-management"
  retention_in_days = 30

  tags = {
    Project = "fish-inventory-management"
  }
}

# --- ALB: shared load balancer, new target group + host-based rule ------

resource "aws_lb_target_group" "im" {
  name        = var.im_short_name
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
    Project = "fish-inventory-management"
  }
}

resource "aws_lb_listener_rule" "im" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 102 # after POP's 100 and SOP's 101, ahead of GL's own bare default_action

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.im.arn
  }

  condition {
    host_header {
      values = [var.im_domain_name]
    }
  }
}

resource "aws_lb_listener_certificate" "im" {
  listener_arn    = aws_lb_listener.https.arn
  certificate_arn = aws_acm_certificate_validation.im.certificate_arn
}

# --- Security group: dedicated, not reused from GL/POP -------------------

resource "aws_security_group" "im_service" {
  name        = "fish-im-service"
  description = "Allow inbound only from the ALB, on the IM container port"
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
    Project = "fish-inventory-management"
  }
}

# --- ECS: shared cluster, dedicated task definition + service -----------

resource "aws_ecs_task_definition" "im" {
  family                   = "fish-inventory-management"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256 # smallest Fargate size to start - same "baseline, not capacity-planned" reasoning as GL's own var.task_cpu
  memory                   = 512
  execution_role_arn       = aws_iam_role.im_ecs_task_execution.arn
  task_role_arn            = aws_iam_role.im_ecs_task.arn

  container_definitions = jsonencode([
    {
      # "bootstrap" - same placeholder-tag precedent as GL's/POP's own
      # task definitions: this revision can't actually start until the
      # first real manual deploy pushes a real image and registers a
      # new revision.
      name      = "fish-inventory-management"
      image     = "${aws_ecr_repository.im.repository_url}:bootstrap"
      essential = true

      portMappings = [
        { containerPort = 8082, protocol = "tcp" }
      ]

      environment = [
        { name = "IM_DB_HOST", value = aws_db_instance.this.address },
        { name = "IM_DB_PORT", value = "5432" },
        { name = "IM_DB_NAME", value = var.im_db_name },
        { name = "IM_DB_USER", value = var.im_db_user },
        { name = "IM_HTTP_PORT", value = "8082" },
        { name = "IM_JWT_ISSUER", value = local.fish_jwt_issuer },
        { name = "IM_JWT_AUDIENCE", value = local.fish_jwt_audience },
        { name = "IM_JWT_JWKS_URL", value = local.fish_jwt_jwks_url },
        # /api suffix required - GL's own inventory/record-receipt and
        # record-issue routes are mounted under route("/api"), same
        # confirmed-not-assumed reasoning as pop.tf's own comment.
        { name = "IM_GL_ENGINE_BASE_URL", value = "https://${var.domain_name}/api" },
        { name = "IM_GL_ENGINE_TENANT_ID", value = var.im_gl_engine_tenant_id },
        { name = "IM_GL_ENGINE_COMPANY_ID", value = var.im_gl_engine_company_id },
        { name = "IM_CORS_ALLOWED_ORIGIN", value = "https://${var.domain_name}" },
        # IM's own Cognito service-account credentials (im_service_account.tf) -
        # built from day one, unlike POP's still-deferred equivalent.
        { name = "IM_GL_ENGINE_COGNITO_REGION", value = var.aws_region },
        { name = "IM_GL_ENGINE_SERVICE_ACCOUNT_CLIENT_ID", value = aws_cognito_user_pool_client.im_service.id },
        { name = "IM_GL_ENGINE_SERVICE_ACCOUNT_USERNAME", value = var.im_service_account_email }
      ]

      secrets = [
        { name = "IM_DB_PASSWORD", valueFrom = aws_secretsmanager_secret.im_db_password.arn },
        { name = "IM_GL_ENGINE_SERVICE_ACCOUNT_PASSWORD", valueFrom = aws_secretsmanager_secret.im_service_account_password.arn },
        { name = "IM_GL_ENGINE_SERVICE_ACCOUNT_CLIENT_SECRET", valueFrom = aws_secretsmanager_secret.im_service_account_client_secret.arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.im.name
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
    Project = "fish-inventory-management"
  }
}

resource "aws_ecs_service" "im" {
  name            = "fish-inventory-management-production"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.im.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.im_service.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.im.arn
    container_name   = "fish-inventory-management"
    container_port   = 8082
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener_rule.im]

  tags = {
    Project = "fish-inventory-management"
  }
}
