# Hosts WEB (the fish-gl-web SPA, a separate repo/git submodule) on the
# same domain as this API (capital.theprodeogroup.com), split by path:
# CloudFront serves the SPA's static assets from S3 by default, and
# forwards /api/* to the existing ALB - see Application.kt's own /api
# route wrapper, which is the backend half of this same decision.
#
# Same two-phase-apply shape as acm.tf/alb.tf's HTTPS work: this file's
# ACM certificate needs a manual CNAME at the external DNS provider
# before `aws_acm_certificate_validation.frontend` can succeed, and the
# domain's own DNS record needs repointing from the ALB to this
# distribution once it exists - see outputs.tf and this repo's own
# deploy notes for the exact steps.

# --- Static asset bucket ----------------------------------------------

resource "aws_s3_bucket" "frontend" {
  # Account id suffix for global uniqueness - S3 bucket names are a
  # single global namespace across every AWS account, not just this one.
  bucket = "${var.project_name}-${var.environment}-web-${data.aws_caller_identity.current.account_id}"

  tags = {
    Project = var.project_name
  }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  block_public_policy     = false # this bucket's own policy grants CloudFront access - see aws_s3_bucket_policy.frontend
  ignore_public_acls      = true
  restrict_public_buckets = false
}

data "aws_caller_identity" "current" {}

# Origin Access Control - CloudFront's currently-recommended mechanism
# for reaching a private S3 bucket (the older Origin Access Identity is
# legacy). No public bucket policy, no S3 static website hosting -
# every request to this bucket must come from this specific
# distribution, enforced by aws_s3_bucket_policy.frontend's
# AWS:SourceArn condition below.
resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${var.project_name}-${var.environment}-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowCloudFrontServicePrincipal"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.frontend.arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.this.arn
          }
        }
      }
    ]
  })
}

# --- Certificate (us-east-1, see versions.tf's provider alias) -------

resource "aws_acm_certificate" "frontend" {
  provider = aws.us_east_1

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Project = var.project_name
  }
}

resource "aws_acm_certificate_validation" "frontend" {
  provider = aws.us_east_1

  certificate_arn = aws_acm_certificate.frontend.arn

  # Same reasoning as aws_acm_certificate_validation.this in acm.tf - no
  # Route 53 zone in this account, so this only polls ACM until the
  # externally-added CNAME has propagated and the cert is ISSUED.
}

# --- Origin-facing listener on the existing ALB -----------------------
#
# CloudFront's HTTPS-origin cert validation requires the origin's
# certificate to cover the exact hostname configured as the CloudFront
# "origin domain name" - the ALB's own AWS-assigned DNS name
# (aws_lb.this.dns_name) isn't covered by aws_acm_certificate.this
# (acm.tf), which is issued for capital.theprodeogroup.com only.
# Rather than give the ALB a second hostname/cert just to satisfy that
# check, CloudFront reaches the ALB over plain HTTP on a dedicated port
# instead - client-to-CloudFront stays HTTPS (this file's default and
# ordered cache behaviors both force redirect-to-https), only the
# CloudFront-to-origin hop within AWS's own network is unencrypted.
# Locked to CloudFront's own IP ranges below, and left off ports 80/443
# entirely so the already-verified redirect/forward behavior on those
# (alb.tf) is untouched.
resource "aws_lb_listener" "cloudfront_origin" {
  load_balancer_arn = aws_lb.this.arn
  port              = 8081
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group_rule" "alb_from_cloudfront" {
  security_group_id = aws_security_group.alb.id
  type              = "ingress"
  description       = "CloudFront origin fetches only (frontend.tf) - not open to the internet at large, unlike ports 80/443"
  from_port         = 8081
  to_port           = 8081
  protocol          = "tcp"
  prefix_list_ids   = [data.aws_ec2_managed_prefix_list.cloudfront.id]
}

# --- Distribution -------------------------------------------------------

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  aliases             = [var.domain_name]

  # PriceClass_All, not the cheaper PriceClass_100 (US/Canada/Europe
  # only) - Sierra Leone and the wider Mano River region are this
  # project's named go-to-market focus (top-level CLAUDE.md), and
  # PriceClass_100 would route those users out to a distant edge
  # location instead of a nearby one. Traffic is low enough right now
  # that the cost difference is negligible either way.
  price_class = "PriceClass_All"

  origin {
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_id                = "web-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  origin {
    domain_name = aws_lb.this.dns_name
    origin_id   = "gl-api-alb"

    custom_origin_config {
      http_port                = aws_lb_listener.cloudfront_origin.port
      https_port               = 443
      origin_protocol_policy   = "http-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = 30
      origin_keepalive_timeout = 5
    }
  }

  default_cache_behavior {
    target_origin_id       = "web-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    # Managed policy "CachingOptimized" - static SPA assets, safe to
    # cache aggressively; no query-string/cookie-based variation.
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
  }

  ordered_cache_behavior {
    path_pattern           = "/api/*"
    target_origin_id       = "gl-api-alb"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    # Managed policy "CachingDisabled" - every response here is a live
    # API call (auth-dependent, mutating), never a cache candidate.
    cache_policy_id = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"

    # Managed policy "AllViewer" - forwards every header (Authorization
    # included, for the JWT bearer token), cookie, and query string
    # through to the ALB unmodified. CachingOptimized's default of
    # stripping most of that is built for static assets, not an API.
    origin_request_policy_id = "216adef6-5c7f-47e4-b989-5492eafa07d3"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.frontend.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  # No custom_error_response SPA fallback (404/403 -> index.html) - WEB
  # has no client-side router yet (single-screen state machine, see its
  # own App.tsx), so there are no deep-linkable routes that would 404
  # against S3 in the first place. A distribution-wide error override
  # would also incorrectly rewrite genuine /api/* 404s into a 200'd
  # index.html, since custom_error_response isn't scoped per behavior -
  # add a CloudFront Function on the default behavior only if/when a
  # router is introduced, rather than reaching for this now.

  tags = {
    Project = var.project_name
  }
}
