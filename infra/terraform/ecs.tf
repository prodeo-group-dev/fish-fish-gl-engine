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
      name      = var.project_name
      # "bootstrap" is a placeholder tag - nothing pushes an image with
      # this exact tag. The first `terraform apply` will create a task
      # definition revision that can't actually start (image not
      # found) - expected, not a bug. The first REAL deploy comes from
      # CI's own `aws ecs register-task-definition` call (deploy.yml's
      # job in ci.yml), which creates a new revision with a real image
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
        { name = "FISH_DB_HOST", value = var.db_host },
        { name = "FISH_DB_PORT", value = var.db_port },
        { name = "FISH_DB_NAME", value = var.db_name },
        { name = "FISH_DB_USER", value = var.db_user },
        { name = "FISH_HTTP_PORT", value = tostring(var.container_port) },
        { name = "FISH_JWT_ISSUER", value = var.jwt_issuer },
        { name = "FISH_JWT_AUDIENCE", value = var.jwt_audience },
        { name = "FISH_JWT_JWKS_URL", value = var.jwt_jwks_url }
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
