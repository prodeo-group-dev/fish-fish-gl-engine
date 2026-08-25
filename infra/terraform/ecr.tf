resource "aws_ecr_repository" "this" {
  name                 = var.project_name
  image_tag_mutability = "IMMUTABLE" # a given tag (git SHA) always refers to the same build - "latest" moves, SHA tags never do

  image_scanning_configuration {
    scan_on_push = true # closes part of docs/GL_Production_Readiness_Assessment.md finding #9 (no dependency-vulnerability scanning) for the image itself, not just Gradle deps
  }

  tags = {
    Project = var.project_name
  }
}

# Keeps the repository from growing unbounded - untagged images (left
# behind once a tag moves, e.g. "latest") expire after 7 days; the
# last 20 tagged images are kept regardless of age, so a rollback to
# a recent-but-not-latest deploy stays possible.
resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after 7 days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep only the last 20 tagged images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["sha-"]
          countType     = "imageCountMoreThan"
          countNumber   = 20
        }
        action = { type = "expire" }
      }
    ]
  })
}
