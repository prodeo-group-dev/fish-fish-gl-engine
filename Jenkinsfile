// GL's Jenkins pipeline (2026-09-04) - replaces .github/workflows/pipeline.yml
// for this repo, given GitHub Actions is confirmed disabled account-wide
// (see infra/terraform/jenkins.tf's own header for the full story).
// pipeline.yml is left in place, dormant, in case Actions ever comes
// back - this file replicates its 4 jobs exactly, same trigger shape
// (Multibranch job type auto-discovers branches + PRs, matching
// `on: pull_request` + `on: push: branches: [master]`), same DAG
// (integration-test and docker-build both only depend on test, so they
// run in `parallel` here rather than silently serializing).

pipeline {
    agent any // single-node controller-as-agent - appropriate for this PoC's single-repo scope

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds() // avoids two builds racing for the same host Docker daemon / Gradle cache on this single-node setup
    }

    environment {
        AWS_REGION                 = 'eu-west-2'
        ECR_REPOSITORY             = '827709230476.dkr.ecr.eu-west-2.amazonaws.com/fish-gl-engine'
        ECS_CLUSTER                = 'fish-gl-engine-production'
        ECS_SERVICE                = 'fish-gl-engine-production'
        ECS_TASK_DEFINITION_FAMILY = 'fish-gl-engine'
        ECS_CONTAINER_NAME         = 'fish-gl-engine'
    }

    stages {
        stage('Checkout') {
            steps {
                // Multibranch's own Git behaviours config (see infra/terraform/README.md's
                // setup notes) has recursive submodule update ticked -
                // fish-common is otherwise left empty, the exact gap
                // pipeline.yml's own header comment says caused silent
                // CI failures once already; don't repeat it.
                checkout scm
                sh 'chmod +x gradlew'
            }
        }

        stage('Unit tests') {
            steps {
                sh './gradlew test --console=plain'
            }
            post {
                always {
                    junit testResults: 'build/test-results/test/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Integration tests + Docker build') {
            parallel {
                stage('Integration tests') {
                    steps {
                        script {
                            // Ephemeral Postgres sidecar via the host
                            // Docker daemon `agent any` already has
                            // access to - simpler than the Docker
                            // Pipeline plugin's `agent { docker {...} }`
                            // for this single-node setup. Random host
                            // port (-p 0:5432), not fixed, so a second
                            // concurrent build can't collide on it if
                            // disableConcurrentBuilds is ever relaxed.
                            def pgName = "pg-${env.BUILD_TAG}"
                            sh """
                                docker run -d --name ${pgName} \\
                                  -e POSTGRES_USER=fish_ci \\
                                  -e POSTGRES_PASSWORD=fish_ci_password \\
                                  -e POSTGRES_DB=fish_dev \\
                                  -p 0:5432 \\
                                  postgres:16
                            """
                            def pgPort = sh(
                                script: "docker port ${pgName} 5432/tcp | cut -d: -f2",
                                returnStdout: true
                            ).trim()
                            sh """
                                for i in \$(seq 1 30); do
                                  docker exec ${pgName} pg_isready -U fish_ci && break
                                  sleep 2
                                done
                            """
                            try {
                                withEnv([
                                    "FISH_DB_HOST=localhost",
                                    "FISH_DB_PORT=${pgPort}",
                                    "FISH_DB_NAME=fish_dev",
                                    "FISH_DB_USER=fish_ci",
                                    "FISH_DB_PASSWORD=fish_ci_password"
                                ]) {
                                    sh './gradlew integrationTest --console=plain'
                                }
                            } finally {
                                sh "docker rm -f ${pgName} || true"
                            }
                        }
                    }
                    post {
                        always {
                            junit testResults: 'build/test-results/integrationTest/*.xml', allowEmptyResults: true
                        }
                    }
                }

                stage('Docker build') {
                    steps {
                        // Build validation only, not pushed - matches
                        // pipeline.yml's own docker-build job exactly.
                        sh 'docker build -t fish-gl-engine:ci .'
                    }
                }
            }
        }

        stage('Verify ECS env vars match ecs.tf') {
            // Catches exactly the class of bug found 2026-09-05: the deploy
            // stage below only ever patches `.image` onto whatever the
            // *previous* live revision already had (the standard
            // aws-actions/amazon-ecs-render-task-definition pattern) - it
            // never re-reads infra/terraform/ecs.tf itself. That's fine
            // IFF the previous revision was already registered from
            // ecs.tf's current state; it silently and permanently
            // propagates drift forever if someone adds/renames/removes an
            // `environment`/`secrets` entry in ecs.tf without a human
            // separately running `terraform apply
            // -replace=aws_ecs_task_definition.this` first (this repo's
            // Terraform state is local-only - see infra/terraform/
            // versions.tf - so this pipeline has no state access and
            // cannot render ecs.tf's real values itself; this check is
            // the closest thing to that without a remote-backend
            // migration). FISH_JWT_SERVICE_AUDIENCE_HR drifted missing
            // this exact way for ~3 days (added to ecs.tf 2026-09-02,
            // never applied via `-replace`, so it was absent from every
            // revision registered since, through at least :49) before
            // HR/Payroll's calls into GL were noticed failing 2026-09-05.
            // Fail loudly instead of deploying more drift.
            when {
                branch 'master'
            }
            steps {
                sh """
                    grep -oE 'name[[:space:]]*=[[:space:]]*"[A-Z0-9_]+"' infra/terraform/ecs.tf \\
                      | grep -oE '"[A-Z0-9_]+"' | tr -d '"' | sort -u > expected-env-names.txt

                    aws ecs describe-task-definition \\
                      --task-definition ${ECS_TASK_DEFINITION_FAMILY} \\
                      --query taskDefinition > task-definition.json

                    jq -r '(.containerDefinitions[0].environment // [])[].name,
                            (.containerDefinitions[0].secrets // [])[].name' \\
                      task-definition.json | sort -u > live-env-names.txt

                    if ! diff -q expected-env-names.txt live-env-names.txt > /dev/null; then
                        echo "ECS env/secret drift detected: the live task definition (${ECS_TASK_DEFINITION_FAMILY})"
                        echo "does not match infra/terraform/ecs.tf's declared environment/secrets block."
                        echo ""
                        echo "Declared in ecs.tf but missing from the live task definition:"
                        comm -23 expected-env-names.txt live-env-names.txt
                        echo ""
                        echo "Live in the task definition but no longer declared in ecs.tf:"
                        comm -13 expected-env-names.txt live-env-names.txt
                        echo ""
                        echo "Fix: from a machine with the real Terraform state, run"
                        echo "  terraform apply -replace=aws_ecs_task_definition.this"
                        echo "then re-run this build."
                        exit 1
                    fi
                """
            }
        }

        stage('Deploy to ECS (production)') {
            when {
                branch 'master' // Multibranch's env.BRANCH_NAME - equivalent to pipeline.yml's `if: github.event_name == 'push' && github.ref == 'refs/heads/master'`; PR builds never reach this stage
            }
            steps {
                script {
                    def imageTag = "sha-${env.GIT_COMMIT}"
                    def ecrHost = ECR_REPOSITORY.split('/')[0]
                    sh """
                        aws ecr get-login-password --region ${AWS_REGION} | \\
                          docker login --username AWS --password-stdin ${ecrHost}
                        docker build -t ${ECR_REPOSITORY}:${imageTag} .
                        docker push ${ECR_REPOSITORY}:${imageTag}
                    """

                    // Raw CLI equivalent of aws-actions/amazon-ecs-render-task-definition -
                    // describe-task-definition returns read-only fields
                    // (taskDefinitionArn/revision/status/requiresAttributes/
                    // compatibilities/registeredAt/registeredBy) that
                    // register-task-definition rejects if echoed back;
                    // jq strips them. Matches the exact manual sequence
                    // already hand-run repeatedly for every deploy this
                    // session. The previous stage has already confirmed
                    // this previous revision's env/secrets match ecs.tf,
                    // so carrying them forward here is safe.
                    sh """
                        jq --arg IMAGE "${ECR_REPOSITORY}:${imageTag}" \\
                          '.containerDefinitions[0].image = \$IMAGE
                           | del(.taskDefinitionArn, .revision, .status,
                                 .requiresAttributes, .compatibilities,
                                 .registeredAt, .registeredBy)' \\
                          task-definition.json > new-task-definition.json

                        NEW_TASK_DEF_ARN=\$(aws ecs register-task-definition \\
                          --cli-input-json file://new-task-definition.json \\
                          --query 'taskDefinition.taskDefinitionArn' --output text)

                        aws ecs update-service \\
                          --cluster ${ECS_CLUSTER} \\
                          --service ${ECS_SERVICE} \\
                          --task-definition "\$NEW_TASK_DEF_ARN"

                        aws ecs wait services-stable \\
                          --cluster ${ECS_CLUSTER} \\
                          --services ${ECS_SERVICE}
                    """
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
