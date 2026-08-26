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
