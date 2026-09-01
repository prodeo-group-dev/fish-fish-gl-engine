# POP (Purchase Order Processing) - Order Fulfillment's backend
# (2026-08-30). Built, tested (149 unit + 14 integration tests), and
# merged this session, but had zero AWS infrastructure until now - the
# live WEB deployment's Fulfilment tab and Supplier portal can't reach
# it at all without this.
#
# Deliberately reuses GL's VPC (network.tf), ECS cluster (ecs.tf),
# ALB (alb.tf), and Cognito pool (cognito.tf) - each is either free to
# share (clusters, Cognito) or would cost real money to duplicate for
# no isolation benefit (a second RDS instance would exceed this
# account's Free Tier hours outright, not just its backup-retention cap
# - variables.tf's own db_backup_retention_days note). Dedicated
# resources only where sharing would be wrong: IAM roles (GL's
# execution role's inline policy is ARN-scoped to GL's own secret),
# the database itself (new logical database, own credentials), the
# OIDC deploy role (trust policy is hardcoded per-repo), and the ACM
# certificate (extending GL's live certificate with a SAN would replace
# it entirely, forcing re-validation of a domain already serving
# production traffic - frontend.tf already established "separate
# certificate per distinct piece of infrastructure" as this repo's own
# precedent).
#
# POP_GL_ENGINE_BEARER_TOKEN is deliberately NOT set below - it's a
# lambda in POP's own Application.kt, evaluated only when the two
# GL-Engine-calling routes (three-way match, supplier payment) are
# actually invoked, not at process startup (verified by reading the
# source before writing this, not assumed). Every other route works
# immediately. How POP authenticates to GL Engine is a deliberately
# deferred decision - see the Order Fulfillment plan's own framing,
# same treatment SES got for eOrder delivery.

# --- ECR ----------------------------------------------------------------

resource "aws_ecr_repository" "pop" {
  name                 = "fish-purchase-order-processing"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project = "fish-purchase-order-processing"
  }
}

resource "aws_ecr_lifecycle_policy" "pop" {
  repository = aws_ecr_repository.pop.name

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

# --- ACM (separate from GL's own certificate - see file header) --------

resource "aws_acm_certificate" "pop" {
  domain_name       = var.pop_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project = "fish-purchase-order-processing"
  }
}

resource "aws_acm_certificate_validation" "pop" {
  certificate_arn = aws_acm_certificate.pop.arn
}

# --- IAM: ECS task execution/task roles (dedicated, not shared with GL) -

resource "aws_iam_role" "pop_ecs_task_execution" {
  name = "fish-pop-ecs-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "pop_ecs_task_execution_managed" {
  role       = aws_iam_role.pop_ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "pop_ecs_task_execution_secrets" {
  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.pop_db_password.arn]
  }
}

resource "aws_iam_role_policy" "pop_ecs_task_execution_secrets" {
  name   = "fish-pop-read-db-secret"
  role   = aws_iam_role.pop_ecs_task_execution.id
  policy = data.aws_iam_policy_document.pop_ecs_task_execution_secrets.json
}

# Was empty until 2026-08-31 (real eOrder email delivery via SES, once
# SES production access was approved - case 178782151300385). Everything
# else POP does (Postgres, the GL Engine's HTTP API) still isn't via the
# AWS SDK, so ses:SendEmail is the one permission this role actually needs.
resource "aws_iam_role" "pop_ecs_task" {
  name = "fish-pop-ecs-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

data "aws_iam_policy_document" "pop_ecs_task_ses" {
  statement {
    effect  = "Allow"
    actions = ["ses:SendEmail", "ses:SendRawEmail"]
    # SES v2 SendEmail's resource-level permissions apply to the
    # *identity* being sent from, not a message ARN - scoped to the
    # verified sender domain (notifications.tf's aws_ses_domain_identity),
    # not "*".
    resources = ["arn:aws:ses:${var.aws_region}:*:identity/${var.pop_notification_from_domain}"]
  }
}

resource "aws_iam_role_policy" "pop_ecs_task_ses" {
  name   = "fish-pop-send-eorder-email"
  role   = aws_iam_role.pop_ecs_task.id
  policy = data.aws_iam_policy_document.pop_ecs_task_ses.json
}

# --- IAM: OIDC deploy role for POP's own GitHub repo --------------------
#
# Reuses the single existing aws_iam_openid_connect_provider.github
# (iam.tf) - only one OIDC provider per URL is allowed per AWS account,
# already created for GL. Cannot reuse GL's github_actions_deploy role
# itself - its trust policy's sub condition is hardcoded to GL's own
# repo.

data "aws_iam_policy_document" "pop_github_actions_assume_role" {
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
      values   = [var.pop_github_oidc_subject]
    }
  }
}

resource "aws_iam_role" "pop_github_actions_deploy" {
  name               = "fish-pop-github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.pop_github_actions_assume_role.json
}

data "aws_iam_policy_document" "pop_github_actions_deploy" {
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
    resources = [aws_ecr_repository.pop.arn]
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
    resources = ["*"] # ECS doesn't support resource-level scoping on these either - same as GL's own role
  }

  statement {
    sid     = "PassTaskRoles"
    effect  = "Allow"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.pop_ecs_task_execution.arn,
      aws_iam_role.pop_ecs_task.arn
    ]
  }
}

resource "aws_iam_role_policy" "pop_github_actions_deploy" {
  name   = "fish-pop-deploy"
  role   = aws_iam_role.pop_github_actions_deploy.id
  policy = data.aws_iam_policy_document.pop_github_actions_deploy.json
}

# --- Secrets Manager (POP's own DB password, not GL's) -------------------

resource "random_password" "pop_db" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "pop_db_password" {
  name        = "fish-purchase-order-processing/production/db-password"
  description = "POP_DB_PASSWORD"
}

resource "aws_secretsmanager_secret_version" "pop_db_password" {
  secret_id     = aws_secretsmanager_secret.pop_db_password.id
  secret_string = random_password.pop_db.result
}

# --- CloudWatch -----------------------------------------------------------

resource "aws_cloudwatch_log_group" "pop" {
  name              = "/ecs/fish-purchase-order-processing"
  retention_in_days = 30

  tags = {
    Project = "fish-purchase-order-processing"
  }
}

# --- ALB: shared load balancer, new target group + host-based rule ------

resource "aws_lb_target_group" "pop" {
  name        = var.pop_short_name
  port        = 8081
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
    Project = "fish-purchase-order-processing"
  }
}

# GL's own HTTPS listener (alb.tf) keeps its bare default_action as the
# catch-all for capital.theprodeogroup.com - this rule only intercepts
# requests for POP's own subdomain, evaluated before the default.
resource "aws_lb_listener_rule" "pop" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.pop.arn
  }

  condition {
    host_header {
      values = [var.pop_domain_name]
    }
  }

  # The rule itself doesn't need POP's own certificate - SNI dispatch on
  # the shared listener still needs the cert attached to the listener,
  # so the listener also gets aws_lb_listener_certificate below.
}

resource "aws_lb_listener_certificate" "pop" {
  listener_arn    = aws_lb_listener.https.arn
  certificate_arn = aws_acm_certificate_validation.pop.certificate_arn
}

# --- Security group: dedicated, not reused from GL --------------------
#
# GL's own aws_security_group.service (network.tf) has its ingress rule
# hardcoded to var.container_port (GL's 8080) - reusing it verbatim
# would not actually open POP's own port 8081 to the ALB. Same shape,
# different port.

resource "aws_security_group" "pop_service" {
  name        = "fish-pop-service"
  description = "Allow inbound only from the ALB, on the POP container port"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB only"
    from_port       = 8081
    to_port         = 8081
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
    Project = "fish-purchase-order-processing"
  }
}

# --- ECS: shared cluster, dedicated task definition + service -----------

resource "aws_ecs_task_definition" "pop" {
  family                   = "fish-purchase-order-processing"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256 # smallest Fargate size to start - same "baseline, not capacity-planned" reasoning as GL's own var.task_cpu
  memory                   = 512
  execution_role_arn       = aws_iam_role.pop_ecs_task_execution.arn
  task_role_arn            = aws_iam_role.pop_ecs_task.arn

  container_definitions = jsonencode([
    {
      # "bootstrap" - same placeholder-tag precedent as GL's own task
      # definition (ecs.tf): this revision can't actually start until
      # the first real manual deploy pushes a real image and registers
      # a new revision.
      name      = "fish-purchase-order-processing"
      image     = "${aws_ecr_repository.pop.repository_url}:bootstrap"
      essential = true

      portMappings = [
        { containerPort = 8081, protocol = "tcp" }
      ]

      environment = [
        { name = "POP_DB_HOST", value = aws_db_instance.this.address },
        { name = "POP_DB_PORT", value = "5432" },
        { name = "POP_DB_NAME", value = var.pop_db_name },
        { name = "POP_DB_USER", value = var.pop_db_user },
        { name = "POP_HTTP_PORT", value = "8081" },
        { name = "POP_JWT_ISSUER", value = local.fish_jwt_issuer },
        { name = "POP_JWT_AUDIENCE", value = local.fish_jwt_audience },
        { name = "POP_JWT_JWKS_URL", value = local.fish_jwt_jwks_url },
        # /api suffix required - GL's own purchasing/record-obligation
        # and record-payment routes are mounted under route("/api"),
        # confirmed by reading GL's Application.kt before writing this,
        # not assumed.
        { name = "POP_GL_ENGINE_BASE_URL", value = "https://${var.domain_name}/api" },
        { name = "POP_GL_ENGINE_TENANT_ID", value = var.pop_gl_engine_tenant_id },
        { name = "POP_GL_ENGINE_COMPANY_ID", value = var.pop_gl_engine_company_id },
        { name = "POP_PORTAL_BASE_URL", value = "https://${var.domain_name}" },
        { name = "POP_CORS_ALLOWED_ORIGIN", value = "https://${var.domain_name}" },
        # Real eOrder email delivery (2026-08-31, SES production access
        # approved) - Application.kt only switches on the real
        # SesOrderNotificationGateway when this is set. Requires
        # notifications.tf's aws_ses_domain_identity to actually be
        # DNS-verified first (a manual step, same two-phase pattern as
        # every ACM cert in this file) - unset/failing until then.
        { name = "POP_NOTIFICATION_FROM_EMAIL", value = "orders@${var.pop_notification_from_domain}" }
        # POP_GL_ENGINE_BEARER_TOKEN deliberately omitted - see file header.
      ]

      secrets = [
        { name = "POP_DB_PASSWORD", valueFrom = aws_secretsmanager_secret.pop_db_password.arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.pop.name
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
    Project = "fish-purchase-order-processing"
  }
}

resource "aws_ecs_service" "pop" {
  name            = "fish-purchase-order-processing-production"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.pop.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.pop_service.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.pop.arn
    container_name   = "fish-purchase-order-processing"
    container_port   = 8081
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener_rule.pop]

  tags = {
    Project = "fish-purchase-order-processing"
  }
}
