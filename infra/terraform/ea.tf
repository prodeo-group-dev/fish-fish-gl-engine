# EA (Enterprise Administration) - deploys the standalone
# persistence/application/web-auth build (Tenant/User/Membership,
# ported out of GL per docs/Tenancy_Administration_Extraction_DDD_Design.md).
#
# Deliberately reuses GL's VPC (network.tf), ECS cluster (ecs.tf), ALB
# (alb.tf), Cognito pool (cognito.tf), and RDS instance (rds.tf) - same
# reasoning as every prior "ecosystem" service's own Terraform (hr.tf's
# own file header, most recently). Dedicated resources only where
# sharing would be wrong: IAM roles, the database itself, the OIDC
# deploy role, and the ACM certificate.
#
# Simpler than POP/SOP/IM/HR's own deploy in one real way: EA doesn't
# call *out* to any other service - it's purely a callee right now, so
# there's no ea_service_account.tf and no outbound Cognito app client.
# The 5 inbound service-account provider slots already coded in
# EA/Auth.kt (EA_JWT_SERVICE_AUDIENCE_GL/POP/SOP/IM/HR) are left unset
# below - each falls back to the primary human verifier per the code's
# own default-parameter pattern, safe to leave for the future pass that
# actually wires GL/POP/SOP/IM/HR to call EA.

# --- ECR ------------------------------------------------------------------

resource "aws_ecr_repository" "ea" {
  name                 = "fish-enterprise-administration"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project = "fish-enterprise-administration"
  }
}

resource "aws_ecr_lifecycle_policy" "ea" {
  repository = aws_ecr_repository.ea.name

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

# --- ACM (separate from every other sibling's own certificate) ------------

resource "aws_acm_certificate" "ea" {
  domain_name       = var.ea_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project = "fish-enterprise-administration"
  }
}

resource "aws_acm_certificate_validation" "ea" {
  certificate_arn = aws_acm_certificate.ea.arn
}

# --- IAM: ECS task execution/task roles (dedicated, not shared) -----------

resource "aws_iam_role" "ea_ecs_task_execution" {
  name = "fish-ea-ecs-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ea_ecs_task_execution_managed" {
  role       = aws_iam_role.ea_ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "ea_ecs_task_execution_secrets" {
  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.ea_db_password.arn]
  }
}

resource "aws_iam_role_policy" "ea_ecs_task_execution_secrets" {
  name   = "fish-ea-read-db-secret"
  role   = aws_iam_role.ea_ecs_task_execution.id
  policy = data.aws_iam_policy_document.ea_ecs_task_execution_secrets.json
}

# EA calls no other sibling's HTTP API - this role exists partly
# because ECS requires a task role distinct from the execution role,
# and (2026-09-06, backlog item 00's support-thread notification email)
# now genuinely needs one real AWS SDK permission of its own.
resource "aws_iam_role" "ea_ecs_task" {
  name = "fish-ea-ecs-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Backlog item 00's SubmitSupportMessageUseCase/ReplyToSupportThreadUseCase
# - same pattern as pop.tf's own pop_ecs_task_ses (SES production access
# already approved, case 178782151300385). Scoped to the same shared
# verified sender domain notifications.tf already provisions, not "*".
data "aws_iam_policy_document" "ea_ecs_task_ses" {
  statement {
    effect    = "Allow"
    actions   = ["ses:SendEmail", "ses:SendRawEmail"]
    resources = ["arn:aws:ses:${var.aws_region}:*:identity/${aws_ses_domain_identity.this.domain}"]
  }
}

resource "aws_iam_role_policy" "ea_ecs_task_ses" {
  name   = "fish-ea-send-support-notification-email"
  role   = aws_iam_role.ea_ecs_task.id
  policy = data.aws_iam_policy_document.ea_ecs_task_ses.json
}

# --- IAM: OIDC deploy role for EA's own GitHub repo ------------------------
#
# Provisioned for consistency with every other sibling even though
# GitHub Actions is disabled account-wide (self-hosted Jenkins is used
# for GL instead) - dormant, zero cost, same "useful if it comes back"
# reasoning as github_runner.tf.

data "aws_iam_policy_document" "ea_github_actions_assume_role" {
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
      values   = [var.ea_github_oidc_subject]
    }
  }
}

resource "aws_iam_role" "ea_github_actions_deploy" {
  name               = "fish-ea-github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.ea_github_actions_assume_role.json
}

data "aws_iam_policy_document" "ea_github_actions_deploy" {
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
    resources = [aws_ecr_repository.ea.arn]
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
      aws_iam_role.ea_ecs_task_execution.arn,
      aws_iam_role.ea_ecs_task.arn
    ]
  }
}

resource "aws_iam_role_policy" "ea_github_actions_deploy" {
  name   = "fish-ea-deploy"
  role   = aws_iam_role.ea_github_actions_deploy.id
  policy = data.aws_iam_policy_document.ea_github_actions_deploy.json
}

# --- Secrets Manager (EA's own DB password) --------------------------------

resource "random_password" "ea_db" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "ea_db_password" {
  name        = "fish-enterprise-administration/production/db-password"
  description = "EA_DB_PASSWORD"
}

resource "aws_secretsmanager_secret_version" "ea_db_password" {
  secret_id     = aws_secretsmanager_secret.ea_db_password.id
  secret_string = random_password.ea_db.result
}

# --- Secrets Manager (backlog item 00's interim operator bearer token) -----
#
# A real secret, not a plain task-definition env var - anyone holding it
# can read/reply across every Tenant's support thread (authorizeOperator()'s
# own KDoc explains why this interim mechanism exists at all rather than a
# full cross-tenant Cognito identity). Generated, not chosen - the platform
# operator retrieves the actual value from Secrets Manager directly, never
# typed or committed anywhere.
#
# **Named tokens, not one shared secret (2026-09-16, code review: "shared
# EA_OPERATOR_TOKEN, no per-operator identity")** - fish-enterprise-
# administration@6324048 replaces the single shared token with a JSON
# object of operator name -> token (`{"operator-1": "..."}` today),
# matching `authorizeOperator()`'s own `EA_OPERATOR_TOKENS` shape. One
# `random_password` per named operator - adding a second real operator
# later means adding one more entry to `var.ea_operator_names`, not
# touching an existing operator's own secret value.
#
# The original singular `EA_OPERATOR_TOKEN` secret/random_password/IAM
# grant briefly existed as a separate resource address alongside this one
# (see git history) while the cutover was in flight - Jenkins deploys only
# patch `.image` on whatever task definition is already live rather than
# reading `container_definitions` from this file, so a manually-pushed
# task definition was needed to actually point the running container at
# this secret (same pattern as the HR_EA_TENANT_ID incident). Confirmed
# stable (task definition revision 33, operator route returning 401 rather
# than 503) before removing the old resources here.
# `var.ea_operator_names` has no default naming any real person -
# deliberately generic ("operator-1") until named operators are actually
# decided, per this project's own "park, don't guess" convention.

resource "random_password" "ea_operator_tokens" {
  for_each = toset(var.ea_operator_names)
  length   = 48
  special  = false
}

resource "aws_secretsmanager_secret" "ea_operator_tokens" {
  name        = "fish-enterprise-administration/production/operator-tokens"
  description = "EA_OPERATOR_TOKENS"
}

resource "aws_secretsmanager_secret_version" "ea_operator_tokens" {
  secret_id = aws_secretsmanager_secret.ea_operator_tokens.id
  secret_string = jsonencode({
    for name in var.ea_operator_names : name => random_password.ea_operator_tokens[name].result
  })
}

data "aws_iam_policy_document" "ea_ecs_task_execution_operator_tokens" {
  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.ea_operator_tokens.arn]
  }
}

resource "aws_iam_role_policy" "ea_ecs_task_execution_operator_tokens" {
  name   = "fish-ea-read-operator-tokens-secret"
  role   = aws_iam_role.ea_ecs_task_execution.id
  policy = data.aws_iam_policy_document.ea_ecs_task_execution_operator_tokens.json
}

# --- CloudWatch -------------------------------------------------------------

resource "aws_cloudwatch_log_group" "ea" {
  name              = "/ecs/fish-enterprise-administration"
  retention_in_days = 30

  tags = {
    Project = "fish-enterprise-administration"
  }
}

# --- ALB: shared load balancer, new target group + host-based rule --------

resource "aws_lb_target_group" "ea" {
  name        = var.ea_short_name
  port        = 8084
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
    Project = "fish-enterprise-administration"
  }
}

resource "aws_lb_listener_rule" "ea" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 105 # after Jenkins' 104, POP/SOP/IM/HR's 100-103

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.ea.arn
  }

  condition {
    host_header {
      values = [var.ea_domain_name]
    }
  }
}

resource "aws_lb_listener_certificate" "ea" {
  listener_arn    = aws_lb_listener.https.arn
  certificate_arn = aws_acm_certificate_validation.ea.certificate_arn
}

# --- Security group: dedicated, not reused from any other sibling ---------

resource "aws_security_group" "ea_service" {
  name        = "fish-ea-service"
  description = "Allow inbound only from the ALB, on the EA container port"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB only"
    from_port       = 8084
    to_port         = 8084
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
    Project = "fish-enterprise-administration"
  }
}

# --- ECS: shared cluster, dedicated task definition + service -------------

resource "aws_ecs_task_definition" "ea" {
  family                   = "fish-enterprise-administration"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256 # smallest Fargate size to start - same "baseline, not capacity-planned" reasoning as every other sibling
  memory                   = 512
  execution_role_arn       = aws_iam_role.ea_ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ea_ecs_task.arn

  container_definitions = jsonencode([
    {
      # "bootstrap" - same placeholder-tag precedent as every other
      # sibling's task definition: this revision can't actually start
      # until the first real manual deploy pushes a real image and
      # registers a new revision.
      name      = "fish-enterprise-administration"
      image     = "${aws_ecr_repository.ea.repository_url}:bootstrap"
      essential = true

      portMappings = [
        { containerPort = 8084, protocol = "tcp" }
      ]

      environment = [
        { name = "EA_DB_HOST", value = aws_db_instance.this.address },
        { name = "EA_DB_PORT", value = "5432" },
        { name = "EA_DB_NAME", value = var.ea_db_name },
        { name = "EA_DB_USER", value = var.ea_db_user },
        { name = "EA_HTTP_PORT", value = "8084" },
        { name = "EA_JWT_ISSUER", value = local.fish_jwt_issuer },
        { name = "EA_JWT_AUDIENCE", value = local.fish_jwt_audience },
        { name = "EA_JWT_JWKS_URL", value = local.fish_jwt_jwks_url },
        { name = "EA_CORS_ALLOWED_ORIGIN", value = "https://${var.domain_name}" },
        # Staff invite accept/decline links (2026-09-11, "Why is Charles
        # Soyinka listed as part of the team when he has not responded
        # to the invite") - InviteStaffMemberUseCase builds the emailed
        # accept URL as "$EA_WEB_APP_BASE_URL/fish?staffInvite=<secret>".
        # Bare origin, no /api suffix - this is a browser-facing link,
        # not a backend-to-backend call like EA_GL_BASE_URL's own /api
        # suffix. var.domain_name confirmed as WEB's real origin via
        # EA_CORS_ALLOWED_ORIGIN's own use of it, just above.
        { name = "EA_WEB_APP_BASE_URL", value = "https://${var.domain_name}" },
        # UC-BO01 Dashboard's own outbound gateways (ComputeDashboardUseCase,
        # 2026-09-06) - required unconditionally by productionModule(), but
        # never added here, which crash-looped every EA deploy since the
        # Dashboard feature shipped (found 2026-09-10 while fixing an
        # unrelated migration issue - the old pre-Dashboard task revision
        # had been silently serving all real traffic the whole time). /api
        # suffix required - these gateways construct paths as "$baseUrl/sales"
        # etc. with no /api of their own, same convention as HR_GL_ENGINE_BASE_URL.
        { name = "EA_SOP_BASE_URL", value = "https://${var.sop_domain_name}/api" },
        { name = "EA_IM_BASE_URL", value = "https://${var.im_domain_name}/api" },
        { name = "EA_GL_BASE_URL", value = "https://${var.domain_name}/api" },
        # Trusts POP/SOP/IM/HR's own service-account tokens when GL
        # forwards them here (the service-account half of GL's EA
        # rewiring - see Auth.kt's authorizeTenant()) - the exact same
        # Cognito app clients GL itself already trusts for
        # FISH_JWT_SERVICE_AUDIENCE/_IM/_HR/_POP (ecs.tf), reused rather
        # than provisioning new ones. EA_JWT_SERVICE_AUDIENCE_GL is
        # deliberately left unset - nothing calls EA using a GL-specific
        # service identity yet.
        { name = "EA_JWT_SERVICE_AUDIENCE_SOP", value = aws_cognito_user_pool_client.sop_service.id },
        { name = "EA_JWT_SERVICE_AUDIENCE_IM", value = aws_cognito_user_pool_client.im_service.id },
        { name = "EA_JWT_SERVICE_AUDIENCE_HR", value = aws_cognito_user_pool_client.hr_service.id },
        { name = "EA_JWT_SERVICE_AUDIENCE_POP", value = aws_cognito_user_pool_client.pop_gl_service.id },
        # Backlog item 00's support thread (docs/EA_Development_Backlog.md) -
        # same verified sender domain as POP's own eOrder email
        # (notifications.tf's aws_ses_domain_identity), a distinct local
        # part. EA_SUPPORT_NOTIFICATION_EMAIL (the operator's own inbox) is
        # a required variable with no default - not guessed here, see
        # var.ea_support_notification_email's own description.
        { name = "EA_NOTIFICATION_FROM_EMAIL", value = "support@${aws_ses_domain_identity.this.domain}" },
        { name = "EA_SUPPORT_NOTIFICATION_EMAIL", value = var.ea_support_notification_email }
      ]

      secrets = [
        { name = "EA_DB_PASSWORD", valueFrom = aws_secretsmanager_secret.ea_db_password.arn },
        # EA_OPERATOR_TOKENS (plural) is what the currently-deployed code
        # actually reads - this container_definitions block only takes
        # effect the next time a task definition is manually registered
        # and pushed (see this resource's own ignore_changes below), not
        # via `terraform apply` against the live service.
        { name = "EA_OPERATOR_TOKENS", valueFrom = aws_secretsmanager_secret.ea_operator_tokens.arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ea.name
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
    Project = "fish-enterprise-administration"
  }
}

resource "aws_ecs_service" "ea" {
  name            = "fish-enterprise-administration-production"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.ea.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.ea_service.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.ea.arn
    container_name   = "fish-enterprise-administration"
    container_port   = 8084
  }

  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener_rule.ea]

  tags = {
    Project = "fish-enterprise-administration"
  }
}
