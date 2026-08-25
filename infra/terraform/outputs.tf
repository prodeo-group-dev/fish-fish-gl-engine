# After `terraform apply`, set these as GitHub Actions repository
# variables (Settings -> Secrets and variables -> Actions -> Variables,
# NOT Secrets - none of these are sensitive) so ci.yml's `deploy` job
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
  description = "The container name inside the task definition - needed by ci.yml's render-task-definition step to know which container to update the image for"
  value       = var.project_name
}

output "github_actions_deploy_role_arn" {
  value = aws_iam_role.github_actions_deploy.arn
}

output "alb_dns_name" {
  description = "Where the app is actually reachable, over plain HTTP, once a real image has been deployed"
  value       = aws_lb.this.dns_name
}
