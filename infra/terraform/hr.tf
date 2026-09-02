# HR/Payroll - "Scope and build HR's HTTP layer" (2026-09-02), the
# parity audit's own standing "HR: no backend" finding. Built from
# scratch this session: a Ktor web layer (Employee CRUD + a "run
# payroll" endpoint triggering RunPayrollUseCase), Exposed persistence
# for Employee only (SalaryAdvance/PayrollTaxRule stay domain-only -
# gross pay/deductions are already caller-supplied, so neither is
# load-bearing for RunPayrollUseCase to work), Cognito service-account
# auth to GL (hr_service_account.tf), and this infrastructure - HR had
# none of it before.
#
# Deliberately reuses GL's VPC (network.tf), ECS cluster (ecs.tf), ALB
# (alb.tf), Cognito pool (cognito.tf), and RDS instance (rds.tf) - same
# reasoning as im.tf's own file header (free/shared resources stay
# shared). Dedicated resources only where sharing would be wrong: IAM
# roles, the database itself, the OIDC deploy role, and the ACM
# certificate - same exceptions every prior "ecosystem" service's own
# Terraform already established.
#
# Unlike IM/POP/SOP, HR has no fixed GL_ENGINE_COMPANY_ID - RunPayrollUseCase's
# own Request.companyId is caller-supplied per call (see
# hr_gl_engine_tenant_id's own KDoc for why: HR's documented scope
# spans multiple Companies under one Tenant, not one fixed Company).

# --- ECR ----------------------------------------------------------------

resource "aws_ecr_repository" "hr" {
  name                 = "fish-hr-payroll"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project = "fish-hr-payroll"
  }
}

resource "aws_ecr_lifecycle_policy" "hr" {
  repository = aws_ecr_repository.hr.name

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

# --- ACM (separate from GL's/POP's/SOP's/IM's own certificates) ---------

resource "aws_acm_certificate" "hr" {
  domain_name       = var.hr_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project = "fish-hr-payroll"
  }
}

resource "aws_acm_certificate_validation" "hr" {
  certificate_arn = aws_acm_certificate.hr.arn
}

# --- IAM: ECS task execution/task roles (dedicated, not shared) ---------

resource "aws_iam_role" "hr_ecs_task_execution" {
  name = "fish-hr-ecs-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "hr_ecs_task_execution_managed" {
  role       = aws_iam_role.hr_ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "hr_ecs_task_execution_secrets" {
  statement {
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.hr_db_password.arn,
      aws_secretsmanager_secret.hr_service_account_password.arn,
      aws_secretsmanager_secret.hr_service_account_client_secret.arn
    ]
  }
}

resource "aws_iam_role_policy" "hr_ecs_task_execution_secrets" {
  name   = "fish-hr-read-db-secret"
  role   = aws_iam_role.hr_ecs_task_execution.id
  policy = data.aws_iam_policy_document.hr_ecs_task_execution_secrets.json
}

# HR calls no AWS SDK service directly (Postgres, the GL Engine's own
# HTTP API) - this role exists only because ECS requires a task role
# distinct from the execution role, not because HR needs any
# permissions on it yet.
resource "aws_iam_role" "hr_ecs_task" {
  name = "fish-hr-ecs-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# --- IAM: OIDC deploy role for HR's own GitHub repo ----------------------

data "aws_iam_policy_document" "hr_github_actions_assume_role" {
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
      values   = [var.hr_github_oidc_subject]
    }
  }
}

resource "aws_iam_role" "hr_github_actions_deploy" {
  name               = "fish-hr-github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.hr_github_actions_assume_role.json
}

data "aws_iam_policy_document" "hr_github_actions_deploy" {
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
    resources = [aws_ecr_repository.hr.arn]
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
      aws_iam_role.hr_ecs_task_execution.arn,
      aws_iam_role.hr_ecs_task.arn
    ]
  }
}

resource "aws_iam_role_policy" "hr_github_actions_deploy" {
  name   = "fish-hr-deploy"
  role   = aws_iam_role.hr_github_actions_deploy.id
  policy = data.aws_iam_policy_document.hr_github_actions_deploy.json
}

# --- Secrets Manager (HR's own DB password, not GL's/IM's/POP's/SOP's) --

resource "random_password" "hr_db" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "hr_db_password" {
  name        = "fish-hr-payroll/production/db-password"
  description = "HR_DB_PASSWORD"
}

resource "aws_secretsmanager_secret_version" "hr_db_password" {
  secret_id     = aws_secretsmanager_secret.hr_db_password.id
  secret_string = random_password.hr_db.result
}

# --- CloudWatch -----------------------------------------------------------

resource "aws_cloudwatch_log_group" "hr" {
  name              = "/ecs/fish-hr-payroll"
  retention_in_days = 30

  tags = {
    Project = "fish-hr-payroll"
  }
}

# --- ALB: shared load balancer, new target group + host-based rule ------

resource "aws_lb_target_group" "hr" {
  name        = var.hr_short_name
  port        = 8083
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
    Project = "fish-hr-payroll"
  }
}

resource "aws_lb_listener_rule" "hr" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 103 # after POP's 100, SOP's 101, IM's 102, ahead of GL's own bare default_action

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.hr.arn
  }

  condition {
    host_header {
      values = [var.hr_domain_name]
    }
  }
}

resource "aws_lb_listener_certificate" "hr" {
  listener_arn    = aws_lb_listener.https.arn
  certificate_arn = aws_acm_certificate_validation.hr.certificate_arn
}

# --- Security group: dedicated, not reused from GL/POP/SOP/IM ------------

resource "aws_security_group" "hr_service" {
  name        = "fish-hr-service"
  description = "Allow inbound only from the ALB, on the HR container port"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB only"
    from_port       = 8083
    to_port         = 8083
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
    Project = "fish-hr-payroll"
  }
}

# --- ECS: shared cluster, dedicated task definition + service -----------

resource "aws_ecs_task_definition" "hr" {
  family                   = "fish-hr-payroll"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256 # smallest Fargate size to start - same "baseline, not capacity-planned" reasoning as GL's own var.task_cpu
  memory                   = 512
  execution_role_arn       = aws_iam_role.hr_ecs_task_execution.arn
  task_role_arn            = aws_iam_role.hr_ecs_task.arn

  container_definitions = jsonencode([
    {
      # "bootstrap" - same placeholder-tag precedent as GL's/IM's own
      # task definitions: this revision can't actually start until the
      # first real manual deploy pushes a real image and registers a
      # new revision.
      name      = "fish-hr-payroll"
      image     = "${aws_ecr_repository.hr.repository_url}:bootstrap"
      essential = true

      portMappings = [
        { containerPort = 8083, protocol = "tcp" }
      ]

      environment = [
        { name = "HR_DB_HOST", value = aws_db_instance.this.address },
        { name = "HR_DB_PORT", value = "5432" },
        { name = "HR_DB_NAME", value = var.hr_db_name },
        { name = "HR_DB_USER", value = var.hr_db_user },
        { name = "HR_HTTP_PORT", value = "8083" },
        { name = "HR_JWT_ISSUER", value = local.fish_jwt_issuer },
        { name = "HR_JWT_AUDIENCE", value = local.fish_jwt_audience },
        { name = "HR_JWT_JWKS_URL", value = local.fish_jwt_jwks_url },
        # /api suffix required - GL's own payroll/leave-accrual routes
        # are mounted under route("/api"), same confirmed-not-assumed
        # reasoning as im.tf's own comment.
        { name = "HR_GL_ENGINE_BASE_URL", value = "https://${var.domain_name}/api" },
        { name = "HR_GL_ENGINE_TENANT_ID", value = var.hr_gl_engine_tenant_id },
        { name = "HR_CORS_ALLOWED_ORIGIN", value = "https://${var.domain_name}" },
        # HR's own Cognito service-account credentials (hr_service_account.tf) -
        # built from day one, matching IM's own precedent.
        { name = "HR_GL_ENGINE_COGNITO_REGION", value = var.aws_region },
        { name = "HR_GL_ENGINE_SERVICE_ACCOUNT_CLIENT_ID", value = aws_cognito_user_pool_client.hr_service.id },
        { name = "HR_GL_ENGINE_SERVICE_ACCOUNT_USERNAME", value = var.hr_service_account_email }
      ]

      secrets = [
        { name = "HR_DB_PASSWORD", valueFrom = aws_secretsmanager_secret.hr_db_password.arn },
        { name = "HR_GL_ENGINE_SERVICE_ACCOUNT_PASSWORD", valueFrom = aws_secretsmanager_secret.hr_service_account_password.arn },
        { name = "HR_GL_ENGINE_SERVICE_ACCOUNT_CLIENT_SECRET", valueFrom = aws_secretsmanager_secret.hr_service_account_client_secret.arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.hr.name
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
    Project = "fish-hr-payroll"
  }
}

resource "aws_ecs_service" "hr" {
  name            = "fish-hr-payroll-production"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.hr.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.hr_service.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.hr.arn
    container_name   = "fish-hr-payroll"
    container_port   = 8083
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener_rule.hr]

  tags = {
    Project = "fish-hr-payroll"
  }
}
