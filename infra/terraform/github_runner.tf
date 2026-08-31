# A self-hosted GitHub Actions runner (2026-08-30) - the scoped fix for
# GitHub's hosted-runner block, not a git-hosting migration (see the
# "Migration considered and rejected" note this decision is grounded
# in). Registers at the ORG level (var.github_organization), so this
# one instance serves every repo in this project, not just fish-fish-gl-engine.
#
# Deliberately holds ZERO standing AWS permissions of its own - job-time
# AWS access still comes entirely from the existing OIDC federation
# (iam.tf), scoped exactly as it already restricts deploys (master-
# branch pushes on the named repo). The only IAM this instance gets is
# AmazonSSMManagedInstanceCore, for remote shell access without opening
# port 22 - see the security group below, which has NO ingress rules at
# all, not even SSH.
#
# Single always-on instance, not an autoscaling/ephemeral pool - the
# simpler of the two shapes scoped out with the user, chosen as the
# starting point given this project's current CI volume (jobs queue
# serially on one runner; migrating to ephemeral later is additive, not
# a rework). Revisit if volume or the "fresh disposable instance per
# job" security property becomes worth the added complexity.
#
# Registration itself is a deliberate manual step, not automated here -
# an org runner registration token expires in ~1 hour, too short-lived
# to wire through a `terraform apply`. See this file's own closing
# comment for the exact steps.

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_iam_role" "github_runner" {
  name = "${var.project_name}-github-runner"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Remote shell access via AWS's own control plane, not SSH - the only
# AWS permission this instance has, and it grants nothing deploy-
# related (job-time AWS credentials come from OIDC, per-job, never from
# this instance's own role).
resource "aws_iam_role_policy_attachment" "github_runner_ssm" {
  role       = aws_iam_role.github_runner.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "github_runner" {
  name = "${var.project_name}-github-runner"
  role = aws_iam_role.github_runner.name
}

# Egress-only - no ingress rules at all. The runner only ever makes
# outbound connections (to GitHub, to AWS STS/ECR, and to SSM for
# remote-session access) - nothing needs to reach it, so nothing is
# allowed to.
resource "aws_security_group" "github_runner" {
  name        = "${var.project_name}-github-runner"
  description = "Self-hosted GitHub Actions runner - egress only, no inbound rules (remote access via SSM, not SSH)"
  vpc_id      = data.aws_vpc.default.id

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

resource "aws_instance" "github_runner" {
  ami                         = data.aws_ami.ubuntu.id
  instance_type               = var.runner_instance_type
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.github_runner.id]
  iam_instance_profile        = aws_iam_instance_profile.github_runner.name
  associate_public_ip_address = true # needed to reach github.com/AWS with no NAT Gateway, same public-subnet-plus-security-group tradeoff network.tf's own note already accepts for the Fargate task

  root_block_device {
    volume_size = var.runner_root_volume_size
    volume_type = "gp3"
  }

  # Stages Docker, JDK 21, and the runner agent - does NOT register the
  # runner (needs a short-lived token this script has no way to obtain
  # unattended). Re-running this script (e.g. via a `terraform taint` +
  # replace) is safe - every step here is idempotent or freshly
  # overwrites its own output.
  user_data = <<-EOF
    #!/bin/bash
    set -euxo pipefail

    apt-get update
    apt-get install -y ca-certificates curl gnupg jq git

    # Docker - native package, not the snap build, so the runner's
    # docker group membership and the daemon socket behave the way
    # `docker build`/`docker push` and the integration-test job's
    # Postgres service container both expect.
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
      > /etc/apt/sources.list.d/docker.list
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin

    # JDK 21 - matches pipeline.yml's actions/setup-java temurin '21'.
    apt-get install -y openjdk-21-jdk

    # A dedicated, unprivileged user - CI jobs never run as root.
    useradd -m -s /bin/bash github-runner
    usermod -aG docker github-runner

    # Runner agent version resolved at boot, not hardcoded - the same
    # "derive it, don't hand-type something that goes stale" reasoning
    # iam.tf's own OIDC thumbprint already uses.
    RUNNER_VERSION=$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest | jq -r '.tag_name' | sed 's/^v//')
    mkdir -p /opt/actions-runner
    cd /opt/actions-runner
    curl -fsSL -o actions-runner.tar.gz "https://github.com/actions/runner/releases/download/v$${RUNNER_VERSION}/actions-runner-linux-x64-$${RUNNER_VERSION}.tar.gz"
    tar xzf actions-runner.tar.gz
    rm actions-runner.tar.gz
    ./bin/installdependencies.sh
    chown -R github-runner:github-runner /opt/actions-runner

    echo "Runner agent staged at /opt/actions-runner on $(hostname) - NOT registered yet. See infra/terraform/github_runner.tf's closing comment for the manual registration step."
  EOF

  tags = {
    Project = var.project_name
    Name    = "${var.project_name}-github-runner"
  }
}

# --- One-time manual registration (can't be automated here - see this
# file's own top comment) ---------------------------------------------
#
# 1. Get a short-lived org registration token (either the GitHub UI:
#    https://github.com/organizations/<org>/settings/actions/runners/new,
#    or `gh api -X POST orgs/<org>/actions/runners/registration-token`
#    with a token that has admin:org scope) - valid ~1 hour.
#
# 2. Connect via SSM (no SSH key/port needed):
#    aws ssm start-session --target <instance-id-from-outputs.tf> \
#      --profile fish-gl-engine --region eu-west-2
#
# 3. As the github-runner user, register and install as a service:
#    sudo su - github-runner
#    cd /opt/actions-runner
#    ./config.sh --url https://github.com/<org> --token <TOKEN> \
#      --unattended --labels self-hosted,eu-west-2
#    exit  # back to the SSM session's default user, which can sudo
#    sudo /opt/actions-runner/svc.sh install github-runner
#    sudo /opt/actions-runner/svc.sh start
#
# 4. Confirm it shows "Idle" at
#    https://github.com/organizations/<org>/settings/actions/runners
#
# 5. Only once confirmed online: switch whichever jobs in
#    pipeline.yml should use it from `runs-on: ubuntu-latest` to
#    `runs-on: [self-hosted, eu-west-2]` - deliberately NOT done as
#    part of this Terraform change, so production CI's routing is a
#    separate, reviewable decision made once the runner is proven
#    working, not a silent side effect of provisioning it.
