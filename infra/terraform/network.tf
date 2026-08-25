# Uses the account's default VPC/subnets rather than provisioning a
# dedicated one - a deliberate starting-point simplification to keep
# this "CD exists at all" baseline small, not a hardened production
# network design. The Fargate task runs in a public subnet with a
# public IP (so it can reach ECR/CloudWatch without a NAT Gateway,
# saving ~$30+/month), locked down at the security-group layer so
# only the ALB can reach it - not "exposed to the internet," but also
# not the private-subnet-plus-NAT-Gateway shape a real production
# network review would likely want. Flagged, not silently accepted as
# final.

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

resource "aws_security_group" "alb" {
  name        = "${var.project_name}-alb"
  description = "Allow inbound HTTP from the internet to the ALB"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # No HTTPS listener yet - needs a domain name and an ACM certificate,
  # neither of which exist anywhere in this project yet. Flagged as a
  # real gap (production traffic over plain HTTP, including JWTs in
  # Authorization headers, is not acceptable long-term), not silently
  # left implying HTTPS already works.
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

resource "aws_security_group" "service" {
  name        = "${var.project_name}-service"
  description = "Allow inbound only from the ALB, on the container port"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "From ALB only"
    from_port       = var.container_port
    to_port         = var.container_port
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
