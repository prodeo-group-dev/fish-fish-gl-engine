resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.project_name}"
  retention_in_days = 30 # a starting default, not a researched retention policy - docs/GL_Production_Readiness_Assessment.md finding #10 (no documented backup/DR story) is still open; a real retention decision belongs there, not guessed here

  tags = {
    Project = var.project_name
  }
}
