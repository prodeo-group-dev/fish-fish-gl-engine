resource "aws_lb" "this" {
  name               = "${var.project_name}-${var.environment}"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = data.aws_subnets.default.ids

  tags = {
    Project = var.project_name
  }
}

resource "aws_lb_target_group" "this" {
  name        = "${var.project_name}-${var.environment}"
  port        = var.container_port
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "ip" # required for awsvpc-network-mode Fargate tasks

  health_check {
    path                = "/health"
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

# HTTPS closed 2026-08-26 (domain confirmed: capital.theprodeogroup.com,
# see acm.tf) - HTTP now just redirects, it no longer forwards directly.
# Kept listening on 80 at all (rather than removed) since that's the
# standard, expected behavior for a public web service - browsers/
# clients that only try http:// still reach something, not a dead port.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# Depends on aws_acm_certificate_validation, not just aws_acm_certificate
# directly - Terraform would otherwise try to create this listener with
# a certificate still in PENDING_VALIDATION, which ELB rejects. See
# acm.tf's own comment on why this is a genuine two-phase apply (the
# validation CNAME has to be added manually at the external DNS
# provider in between).
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.this.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }
}
