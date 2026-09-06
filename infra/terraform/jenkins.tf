# Self-hosted Jenkins (2026-09-04) - replaces GitHub Actions for GL only,
# as a proof of concept, given Actions is confirmed disabled account-wide
# for prodeo-group-dev: `gh workflow run` returns HTTP 422 "Actions has
# been disabled for this user", even though this repo's own Actions
# permissions show enabled:true and the workflow has a workflow_dispatch
# trigger. Confirmed to be a user/account-level block on Actions itself,
# not a repo-config or hosted-runner-specific issue - which is exactly
# why github_runner.tf (a self-hosted GitHub Actions runner) is a dead
# end: a self-hosted runner only changes WHERE a job executes once
# Actions agrees to schedule it, and here Actions refuses to create the
# run at all. See that file's own updated header note. github_runner.tf
# is left in place, dormant (never applied, costs nothing) rather than
# removed, in case the account-wide block ever narrows or lifts.
#
# Jenkins sidesteps the block entirely: GitHub *webhooks* (which trigger
# Jenkins builds) are a separate feature from Actions and are
# unaffected. Scope: GL only. POP/SOP/IM/HR migration is a deliberate
# follow-on, not part of this pass.
#
# ALB-fronted with its own subdomain + ACM cert, confirmed with the user
# directly rather than assumed: GitHub's webhook source IPs are a large
# published range, so a bare-port security group restricted enough to
# protect the admin login would also block the webhook - they can't be
# cleanly separated without TLS termination at a shared front door. Same
# pattern pop.tf/sop.tf/im.tf/hr.tf already use.
#
# Reuses github_runner.tf's own data "aws_ami" "ubuntu" - same directory,
# Terraform merges every .tf file into one config, so it's not
# redeclared here.

# --- IAM: EC2 instance role, mirrors github_actions_deploy's permission
# SHAPE (iam.tf), not its trust mechanism - this is an instance role,
# not an OIDC-federated one. Per this repo's own per-caller-gets-its-
# own-role precedent (pop_github_actions_deploy, sop_..., etc.), reuse
# the shape, never the role itself. -------------------------------------

resource "aws_iam_role" "jenkins" {
  name = "${var.project_name}-jenkins"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "jenkins_ssm" {
  role       = aws_iam_role.jenkins.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "jenkins_deploy" {
  statement {
    sid       = "PushToEcr"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # GetAuthorizationToken has no resource-level scoping - same as iam.tf's own note
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
    # aws_ecr_repository.ea added 2026-09-06 - EA rolled onto this same
    # Jenkins controller as a second Multibranch job, per
    # project_self_hosted_jenkins_gl's own "deliberate, separate
    # follow-on" note.
    resources = [aws_ecr_repository.this.arn, aws_ecr_repository.ea.arn]
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
    resources = ["*"] # same ECS resource-scoping limitation iam.tf already documents
  }

  statement {
    sid     = "PassTaskRoles"
    effect  = "Allow"
    actions = ["iam:PassRole"]
    # aws_iam_role.ea_ecs_task_execution/ea_ecs_task added 2026-09-06 -
    # missed when PushToThisRepoOnly above was extended for EA; the
    # ECR fix alone wasn't enough, register-task-definition also needs
    # PassRole on both roles the task definition itself references.
    resources = [
      aws_iam_role.ecs_task_execution.arn,
      aws_iam_role.ecs_task.arn,
      aws_iam_role.ea_ecs_task_execution.arn,
      aws_iam_role.ea_ecs_task.arn
    ]
  }
}

resource "aws_iam_role_policy" "jenkins_deploy" {
  name   = "${var.project_name}-jenkins-deploy"
  role   = aws_iam_role.jenkins.id
  policy = data.aws_iam_policy_document.jenkins_deploy.json
}

# Read-only access to its OWN admin-password secret only, nothing else -
# narrowest grant that lets user_data fetch the value it needs at boot.
data "aws_iam_policy_document" "jenkins_read_own_secret" {
  statement {
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.jenkins_admin_password.arn]
  }
}

resource "aws_iam_role_policy" "jenkins_read_own_secret" {
  name   = "${var.project_name}-jenkins-read-admin-secret"
  role   = aws_iam_role.jenkins.id
  policy = data.aws_iam_policy_document.jenkins_read_own_secret.json
}

resource "aws_iam_instance_profile" "jenkins" {
  name = "${var.project_name}-jenkins"
  role = aws_iam_role.jenkins.name
}

# --- Secrets Manager: Jenkins admin password, same shape as secrets.tf/
# pitch_basic_auth.tf - nobody, including an AI agent, ever types or
# sees the plaintext; user_data fetches it at boot. ----------------------

resource "random_password" "jenkins_admin" {
  length  = 24
  special = false # avoids shell-quoting hazards when user_data embeds it in a groovy round-trip
}

resource "aws_secretsmanager_secret" "jenkins_admin_password" {
  name        = "${var.project_name}/${var.environment}/jenkins-admin-password"
  description = "Initial Jenkins admin password, consumed by jenkins.tf's user_data at first boot to bypass the setup wizard."
}

resource "aws_secretsmanager_secret_version" "jenkins_admin_password" {
  secret_id     = aws_secretsmanager_secret.jenkins_admin_password.id
  secret_string = random_password.jenkins_admin.result
}

# --- Security group: DELIBERATE difference from github_runner.tf's
# zero-ingress posture - Jenkins needs its web UI reachable (GitHub's
# webhook + human admin access), unlike a runner which only ever makes
# outbound connections. Ingress from the ALB only, never the internet
# directly, no SSH (remote access via SSM). -----------------------------

resource "aws_security_group" "jenkins" {
  name        = "${var.project_name}-jenkins"
  description = "Jenkins controller - inbound only from the ALB on 8080, no direct internet ingress, no SSH (remote access via SSM)"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB only"
    from_port       = 8080
    to_port         = 8080
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
    Project = var.project_name
  }
}

# --- EC2 instance ---------------------------------------------------------

resource "aws_instance" "jenkins" {
  ami                         = data.aws_ami.ubuntu.id # reused from github_runner.tf
  instance_type               = var.jenkins_instance_type
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.jenkins.id]
  iam_instance_profile        = aws_iam_instance_profile.jenkins.name
  associate_public_ip_address = true # same public-subnet/no-NAT-Gateway tradeoff as github_runner.tf and the Fargate task

  root_block_device {
    volume_size = var.jenkins_root_volume_size
    volume_type = "gp3"
  }

  # Installs Docker, JDK 21, AWS CLI v2, and Jenkins itself, then
  # bypasses Jenkins' interactive setup wizard by pre-creating the admin
  # account via init.groovy.d. The admin-password fetch/groovy-write is
  # deliberately wrapped in `set +x`/`set -x` - with tracing left on
  # (needed everywhere else in this script for journalctl/cloud-init
  # debuggability), the plaintext password would otherwise be echoed
  # into /var/log/cloud-init-output.log, readable by anyone with EC2
  # console/log access. Verified at deploy time that it does NOT appear
  # in that log (see the plan's own verification step).
  user_data = <<-EOF
    #!/bin/bash
    set -euxo pipefail

    # 4GB swap file - this account is restricted to Free Tier-eligible
    # instance types only (t3.micro, 1 vCPU/1GB), confirmed via a failed
    # apply attempting t3.large (see variables.tf's own note on
    # jenkins_instance_type) - almost certainly the same abuse-review
    # restriction that disabled GitHub Actions. 1GB physical RAM alone
    # is genuinely too little for the Jenkins JVM, Gradle, and a Docker
    # daemon (plus a Postgres sidecar during integration tests) running
    # concurrently; swap compensates at the cost of slower builds under
    # real memory pressure, not a guess at sufficiency.
    fallocate -l 4G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
    # Favor swapping out idle pages over killing processes under memory
    # pressure (default vm.swappiness=60 is tuned for desktops, not a
    # memory-constrained CI box) - still allows Docker/Gradle's own
    # OOM-prone behavior to be a real risk, not eliminated, just reduced.
    echo 'vm.swappiness=80' >> /etc/sysctl.conf
    sysctl -p

    apt-get update
    apt-get install -y ca-certificates curl gnupg jq git unzip fontconfig

    # Docker - native package, not the snap build, so the daemon socket
    # and group membership behave the way `docker build`/`docker push`
    # and the integration-test job's Postgres sidecar both expect. Same
    # reasoning as github_runner.tf.
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
      > /etc/apt/sources.list.d/docker.list
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin

    # JDK 21 Temurin - matches pipeline.yml's setup-java config, also
    # what Jenkins itself (2.4xx LTS) requires to run.
    apt-get install -y openjdk-21-jdk

    # AWS CLI v2 - apt's own `awscli` package is a stale v1; the deploy
    # stage's `aws ecs wait services-stable` needs a current v2 CLI.
    curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
    unzip -q /tmp/awscliv2.zip -d /tmp
    /tmp/aws/install
    rm -rf /tmp/awscliv2.zip /tmp/aws

    # Jenkins itself, via the official apt repo. Jenkins rotates this
    # key's filename periodically (was jenkins.io-2023.key, now the
    # 2026 key) - an old filename 404s or, worse, resolves to a key
    # that doesn't match the repo's current signature, failing with
    # NO_PUBKEY at apt-get update. Confirmed against Jenkins' own
    # current install docs 2026-09-04.
    install -m 0755 -d /usr/share/keyrings
    curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key -o /usr/share/keyrings/jenkins-keyring.asc
    echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/" \
      > /etc/apt/sources.list.d/jenkins.list
    apt-get update
    apt-get install -y jenkins
    systemctl stop jenkins

    # jenkins user needs docker access for the docker-build stage and
    # the integrationTest Postgres sidecar - same reasoning as
    # github_runner.tf's own github-runner user.
    usermod -aG docker jenkins

    # Skip the interactive setup wizard entirely.
    mkdir -p /etc/systemd/system/jenkins.service.d
    cat > /etc/systemd/system/jenkins.service.d/override.conf <<'OVERRIDE'
    [Service]
    Environment="JAVA_OPTS=-Djenkins.install.runSetupWizard=false"
    OVERRIDE

    # Sensitive block - tracing off so the plaintext password never
    # reaches cloud-init's own log file.
    set +x
    ADMIN_PASSWORD=$(aws secretsmanager get-secret-value \
      --region ${var.aws_region} \
      --secret-id "${var.project_name}/${var.environment}/jenkins-admin-password" \
      --query SecretString --output text)

    mkdir -p /var/lib/jenkins/init.groovy.d
    cat > /var/lib/jenkins/init.groovy.d/basic-security.groovy <<GROOVY
    import jenkins.model.*
    import hudson.security.*

    def instance = Jenkins.get()
    def hudsonRealm = new HudsonPrivateSecurityRealm(false)
    hudsonRealm.createAccount("admin", "$ADMIN_PASSWORD")
    instance.setSecurityRealm(hudsonRealm)

    def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
    strategy.setAllowAnonymousRead(false)
    instance.setAuthorizationStrategy(strategy)
    instance.save()
    GROOVY

    unset ADMIN_PASSWORD
    set -x

    chown -R jenkins:jenkins /var/lib/jenkins/init.groovy.d
    systemctl daemon-reload
    systemctl enable jenkins
    systemctl start jenkins

    echo "Jenkins installed and started on $(hostname). Admin password in Secrets Manager: ${var.project_name}/${var.environment}/jenkins-admin-password. See infra/terraform/README.md for the remaining manual steps (plugin install, GitHub credential, multibranch job, webhook)."
  EOF

  tags = {
    Project = var.project_name
    Name    = "${var.project_name}-jenkins"
  }
}

# --- ALB: shared load balancer, new target group + host-based rule,
# same pattern as pop.tf/sop.tf/im.tf/hr.tf - one real mechanical
# difference: Jenkins is a bare EC2 instance (target_type = "instance"
# + an explicit attachment), not a Fargate task (target_type = "ip",
# wired automatically via the ECS service's own load_balancer block). --

resource "aws_acm_certificate" "jenkins" {
  domain_name       = var.jenkins_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project = var.project_name
  }
}

resource "aws_acm_certificate_validation" "jenkins" {
  certificate_arn = aws_acm_certificate.jenkins.arn
}

resource "aws_lb_target_group" "jenkins" {
  name        = "${var.project_name}-jenkins"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "instance"

  health_check {
    path                = "/login" # Jenkins has no dedicated /health route; /login returns 200 once fully up, even unauthenticated
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }

  tags = {
    Project = var.project_name
  }
}

resource "aws_lb_target_group_attachment" "jenkins" {
  target_group_arn = aws_lb_target_group.jenkins.arn
  target_id        = aws_instance.jenkins.id
  port             = 8080
}

resource "aws_lb_listener_rule" "jenkins" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 104 # after POP's 100, SOP's 101, IM's 102, HR's 103

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.jenkins.arn
  }

  condition {
    host_header {
      values = [var.jenkins_domain_name]
    }
  }
}

resource "aws_lb_listener_certificate" "jenkins" {
  listener_arn    = aws_lb_listener.https.arn
  certificate_arn = aws_acm_certificate_validation.jenkins.certificate_arn
}
