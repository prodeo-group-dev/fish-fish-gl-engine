resource "aws_ecs_cluster" "this" {
  name = "${var.project_name}-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "disabled" # a real cost/observability tradeoff, not an oversight - enable once docs/GL_Production_Readiness_Assessment.md finding #5 (metrics) gets built and there's an actual reason to want cluster-level dashboards
  }

  tags = {
    Project = var.project_name
  }
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.project_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name = var.project_name
      # "bootstrap" is a placeholder tag - nothing pushes an image with
      # this exact tag. The first `terraform apply` will create a task
      # definition revision that can't actually start (image not
      # found) - expected, not a bug. The first REAL deploy comes from
      # CI's own `aws ecs register-task-definition` call (the `deploy`
      # job in pipeline.yml), which creates a new revision with a real image
      # and points the service at it - this resource's own
      # container_definitions is then permanently stale, deliberately
      # (see the lifecycle block below).
      image     = "${aws_ecr_repository.this.repository_url}:bootstrap"
      essential = true

      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      environment = [
        { name = "FISH_DB_HOST", value = aws_db_instance.this.address },
        { name = "FISH_DB_PORT", value = var.db_port },
        { name = "FISH_DB_NAME", value = var.db_name },
        { name = "FISH_DB_USER", value = var.db_user },
        { name = "FISH_HTTP_PORT", value = tostring(var.container_port) },
        { name = "FISH_JWT_ISSUER", value = local.fish_jwt_issuer },
        { name = "FISH_JWT_AUDIENCE", value = local.fish_jwt_audience },
        # Trusts SOP's service-account identity too (sop_service_account.tf) -
        # a second, deliberately-provisioned Cognito app client, additive to
        # (never a replacement for) the "web" client's own audience above.
        { name = "FISH_JWT_SERVICE_AUDIENCE", value = aws_cognito_user_pool_client.sop_service.id },
        { name = "FISH_JWT_SERVICE_AUDIENCE_IM", value = aws_cognito_user_pool_client.im_service.id },
        { name = "FISH_JWT_SERVICE_AUDIENCE_HR", value = aws_cognito_user_pool_client.hr_service.id },
        # Trusts POP's own service-account identity (pop_gl_service_account.tf) -
        # closes docs/POP_GL_Service_Account_Closure_Plan.md.
        { name = "FISH_JWT_SERVICE_AUDIENCE_POP", value = aws_cognito_user_pool_client.pop_gl_service.id },
        { name = "FISH_JWT_JWKS_URL", value = local.fish_jwt_jwks_url },
        # Was unset ("wherever fish-gl-web ends up actually hosted,
        # which isn't decided yet" - Application.kt's own comment) until
        # frontend.tf decided it: same domain as the API, via CloudFront.
        # Needed even though the browser now sees this as same-origin -
        # Ktor's CORS plugin (unlike a browser) actively 403s any request
        # whose Origin header isn't allowlisted, and some browsers send
        # Origin on same-origin POSTs too. Confirmed missing 2026-08-27
        # (POST /api/tenants 403ing for a real onboarding attempt).
        { name = "FISH_CORS_ALLOWED_ORIGIN", value = "https://${var.domain_name}" },
        # CognitoAdminPhoneVerificationChecker's own lookup target (the
        # 2026-08-27 admin-phone KYB extension - see tenant.kt) - the
        # ECS task role's IAM policy (notifications.tf) is scoped to
        # exactly this one user pool's AdminGetUser action.
        { name = "FISH_COGNITO_USER_POOL_ID", value = aws_cognito_user_pool.this.id },
        # SesStaffInviteNotificationGateway's sender identity (2026-08-31,
        # "Business Staff onboarding") - same verified mail.theprodeogroup.com
        # domain identity Cognito and POP's own eOrder gateway already use,
        # just a different local part. The ECS task role's own IAM policy
        # (notifications.tf's ecs_task_ses) is scoped to exactly this identity.
        { name = "GL_STAFF_INVITE_FROM_EMAIL", value = "team@mail.${var.root_domain}" }
      ]

      secrets = [
        { name = "FISH_DB_PASSWORD", valueFrom = aws_secretsmanager_secret.db_password.arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])

  lifecycle {
    # CI registers new task definition revisions directly (with the
    # real, just-built image) on every deploy - Terraform managing
    # container_definitions after the first apply would fight CI for
    # ownership of the image tag, reverting every deploy back to
    # "bootstrap" on the next `terraform apply`. Terraform still owns
    # everything else about the task definition (cpu/memory/roles) -
    # only the container spec itself is CI's, from here on.
    #
    # Real consequence, not just theoretical: ignore_changes applies to
    # EVERY source of diff, including deliberate edits to this file -
    # not just externally-caused drift. When FISH_DB_HOST changed from
    # var.db_host to aws_db_instance.this.address (rds.tf, 2026-08-26),
    # a plain `terraform apply` did NOT update the already-applied
    # revision's environment variables, because this ignore_changes
    # rule treats container_definitions as always matching state,
    # regardless of what the config now says. Getting a corrected env
    # var into a real revision needs `terraform apply
    # -replace=aws_ecs_task_definition.this` - and even then, the
    # SERVICE won't switch to that new revision on its own either (see
    # aws_ecs_service.this's own ignore_changes below) until CI's next
    # deploy explicitly calls update-service, which it does on every
    # push to master regardless.
    ignore_changes = [container_definitions]
  }

  tags = {
    Project = var.project_name
  }
}

resource "aws_ecs_service" "this" {
  name            = "${var.project_name}-${var.environment}"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.service.id]
    assign_public_ip = true # public subnet, no NAT Gateway - see network.tf's own caveat
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = var.project_name
    container_port   = var.container_port
  }

  # Deploys happen via CI calling `aws ecs update-service --force-new-
  # deployment` after registering a new task definition revision, not
  # via `terraform apply` - same ignore_changes reasoning as the task
  # definition's container_definitions above.
  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.http]

  tags = {
    Project = var.project_name
  }
}
