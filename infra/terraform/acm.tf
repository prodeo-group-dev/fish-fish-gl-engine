# TLS certificate for var.domain_name (capital.theprodeogroup.com,
# confirmed 2026-08-26 - closes the "no HTTPS" gap flagged in alb.tf/
# network.tf since this infra was first written). DNS-validated, not
# email-validated - more reliable, no dependency on an inbox existing
# at admin@/webmaster@ the domain.
#
# theprodeogroup.com's DNS lives at an external registrar/DNS provider,
# not Route 53 in this AWS account - so this resource can REQUEST the
# certificate, but validating it needs a CNAME record added manually
# at that external provider. This is a genuine two-phase apply, not a
# single terraform apply:
#
#   1. `terraform apply` (this creates the cert in PENDING_VALIDATION
#      and outputs the CNAME record to add - see outputs.tf's
#      acm_validation_record)
#   2. Add that CNAME record at the external DNS provider
#   3. `terraform apply` again - aws_acm_certificate_validation waits
#      (up to its default timeout) for ACM to see the CNAME and mark
#      the certificate ISSUED, then the HTTPS listener in alb.tf
#      (which depends on the validated certificate) can actually be
#      created
#
# Must be in the same AWS region as the ALB (var.aws_region, eu-west-2)
# - ACM certificates used by an ALB listener are region-scoped to that
# ALB's own region, unlike CloudFront which needs us-east-1 specifically.
resource "aws_acm_certificate" "this" {
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project = var.project_name
  }
}

resource "aws_acm_certificate_validation" "this" {
  certificate_arn = aws_acm_certificate.this.arn

  # No validation_record_fqdns pointing at a Route 53 resource here,
  # deliberately - there's no Route 53 zone in this account to manage
  # (see the file header). This resource just polls ACM until the
  # certificate's status becomes ISSUED, which only happens once the
  # CNAME has been added externally and DNS has propagated - it does
  # not create or verify the CNAME itself.
}
