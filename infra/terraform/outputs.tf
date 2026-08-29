# After `terraform apply`, set these as GitHub Actions repository
# variables (Settings -> Secrets and variables -> Actions -> Variables,
# NOT Secrets - none of these are sensitive) so pipeline.yml's `deploy` job
# has somewhere real to deploy to. See infra/terraform/README.md.

output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "ecs_service_name" {
  value = aws_ecs_service.this.name
}

output "ecs_task_definition_family" {
  value = aws_ecs_task_definition.this.family
}

output "container_name" {
  description = "The container name inside the task definition - needed by pipeline.yml's render-task-definition step to know which container to update the image for"
  value       = var.project_name
}

output "github_actions_deploy_role_arn" {
  value = aws_iam_role.github_actions_deploy.arn
}

output "alb_dns_name" {
  description = "Where the app is actually reachable once a real image has been deployed AND the DNS steps below are done. Add a CNAME at your external DNS provider: capital.theprodeogroup.com -> this value."
  value       = aws_lb.this.dns_name
}

# Cognito outputs - the frontend's own config needs the pool/client ids
# directly (not the derived FISH_JWT_* values below, which are for the
# backend's verifier); nothing here is sensitive, a Cognito app client
# id/pool id is not a secret (no client secret exists for this public
# client - see cognito.tf).
output "cognito_user_pool_id" {
  value = aws_cognito_user_pool.this.id
}

output "cognito_user_pool_client_id" {
  value = aws_cognito_user_pool_client.web.id
}

output "fish_jwt_issuer" {
  description = "Matches what GL's own ECS task definition is already configured with (cognito.tf) - included here so it can be double-checked or reused, not because anything still needs to be set by hand."
  value       = local.fish_jwt_issuer
}

output "fish_jwt_jwks_url" {
  value = local.fish_jwt_jwks_url
}

# theprodeogroup.com's DNS is external (not Route 53 in this account) -
# this record has to be added by hand wherever that DNS actually lives,
# BEFORE the second `terraform apply` that creates the HTTPS listener
# can succeed (aws_acm_certificate_validation blocks on it). See acm.tf.
output "acm_validation_record" {
  description = "DNS validation record to add at the external provider for capital.theprodeogroup.com - a CNAME: name -> value, exactly as ACM generated them. Add this FIRST, before re-running terraform apply."
  value = {
    name  = tolist(aws_acm_certificate.this.domain_validation_options)[0].resource_record_name
    type  = tolist(aws_acm_certificate.this.domain_validation_options)[0].resource_record_type
    value = tolist(aws_acm_certificate.this.domain_validation_options)[0].resource_record_value
  }
}

# frontend.tf's own certificate (us-east-1, separate resource from the
# one above) - almost always the identical record as acm_validation_record
# since both certs are for the same domain_name, but ACM doesn't
# guarantee that across separate certificate requests, so this is
# output and should be checked/added explicitly rather than assumed.
output "frontend_acm_validation_record" {
  description = "DNS validation record for frontend.tf's CloudFront certificate. Add at the external DNS provider before the terraform apply that creates aws_acm_certificate_validation.frontend."
  value = {
    name  = tolist(aws_acm_certificate.frontend.domain_validation_options)[0].resource_record_name
    type  = tolist(aws_acm_certificate.frontend.domain_validation_options)[0].resource_record_type
    value = tolist(aws_acm_certificate.frontend.domain_validation_options)[0].resource_record_value
  }
}

output "frontend_s3_bucket" {
  description = "Upload WEB's `npm run build` output (dist/) here, then invalidate cloudfront_distribution_id's cache."
  value       = aws_s3_bucket.frontend.id
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.this.id
}

output "cloudfront_domain_name" {
  description = "capital.theprodeogroup.com's DNS record needs repointing from alb_dns_name to THIS value (a CNAME) once the distribution is deployed - the cutover step, done after everything else in this file is live and confirmed working."
  value       = aws_cloudfront_distribution.this.domain_name
}

# notifications.tf's SES domain identity - both records must be added
# at the external DNS provider (mail.theprodeogroup.com's own zone,
# same external-DNS caveat as everything else in this file) before
# Cognito's email_configuration will actually work.
output "ses_domain_verification_record" {
  description = "TXT record to add: name is _amazonses.mail.theprodeogroup.com, value is this output."
  value       = aws_ses_domain_identity.this.verification_token
}

output "ses_dkim_records" {
  description = "3 CNAME records to add, one per token: name is '<token>._domainkey.mail.theprodeogroup.com', value is '<token>.dkim.amazonses.com' for each token in this list."
  value       = aws_ses_domain_dkim.this.dkim_tokens
}
