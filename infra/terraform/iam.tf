# --- GitHub OIDC federation ------------------------------------------
#
# No long-lived AWS access keys stored in GitHub secrets - the deploy
# job authenticates via OIDC (aws-actions/configure-aws-credentials'
# `role-to-assume`), a short-lived token GitHub mints per workflow run.
#
# AWS allows only ONE IAM OIDC provider per provider URL per account.
# If token.actions.githubusercontent.com is already registered in this
# AWS account (common if any other repo already uses GitHub OIDC),
# `terraform apply` will fail on this resource - delete this block and
# add a `data "aws_iam_openid_connect_provider"` lookup instead, then
# reference `data.aws_iam_openid_connect_provider.github.arn` in the
# trust policy below.
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  # GitHub's well-known OIDC thumbprint as of 2026-08-22 - GitHub has
  # rotated this before and could again; verify at
  # https://github.blog before `apply` rather than trusting this is
  # still current.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea"]
}

data "aws_iam_policy_document" "github_actions_assume_role" {
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

    # Restricts to master-branch pushes on this specific repo only -
    # not any branch, not any PR, not any other repo under the org.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [var.github_oidc_subject]
    }
  }
}

resource "aws_iam_role" "github_actions_deploy" {
  name               = "${var.project_name}-github-actions-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume_role.json
}

data "aws_iam_policy_document" "github_actions_deploy" {
  statement {
    sid    = "PushToEcr"
    effect = "Allow"
    actions = [
      "ecr:GetAuthorizationToken"
    ]
    resources = ["*"] # GetAuthorizationToken doesn't support resource-level scoping - an ECR/IAM constraint, not a broadening choice made here
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
    resources = [aws_ecr_repository.this.arn]
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
    resources = ["*"] # RegisterTaskDefinition doesn't support resource-level scoping either; UpdateService/DescribeServices are narrowed via the condition below instead
  }

  statement {
    sid    = "PassTaskRoles"
    effect = "Allow"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.ecs_task_execution.arn,
      aws_iam_role.ecs_task.arn
    ]
  }
}

resource "aws_iam_role_policy" "github_actions_deploy" {
  name   = "${var.project_name}-deploy"
  role   = aws_iam_role.github_actions_deploy.id
  policy = data.aws_iam_policy_document.github_actions_deploy.json
}

# --- ECS task execution role ------------------------------------------
#
# Used by the ECS agent itself (not the running application) to pull
# the image from ECR, write logs to CloudWatch, and read the DB
# password out of Secrets Manager to inject as a container env var.

resource "aws_iam_role" "ecs_task_execution" {
  name = "${var.project_name}-ecs-task-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_managed" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "ecs_task_execution_secrets" {
  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.db_password.arn]
  }
}

resource "aws_iam_role_policy" "ecs_task_execution_secrets" {
  name   = "${var.project_name}-read-db-secret"
  role   = aws_iam_role.ecs_task_execution.id
  policy = data.aws_iam_policy_document.ecs_task_execution_secrets.json
}

# --- ECS task role ------------------------------------------------------
#
# Used by the application itself, inside the running container.
# Deliberately empty for now - the app makes no AWS API calls of its
# own (it talks to Postgres and an external IdP, neither via the AWS
# SDK) - a distinct role from the execution role above regardless,
# since ECS requires one and giving the app the execution role's own
# permissions (Secrets Manager read, ECR pull) would be a real,
# avoidable over-grant.

resource "aws_iam_role" "ecs_task" {
  name = "${var.project_name}-ecs-task"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}
