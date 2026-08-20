# docs/DDD_Design.md Section 10.19 - the GL Engine's first container
# image. Multi-stage: a full JDK+Gradle build stage produces the fat
# jar (Ktor's own `buildFatJar` task, io.ktor.plugin), a slim JRE
# runtime stage runs it - the runtime image never carries the Gradle
# wrapper, build cache, or source tree, only the one runnable jar.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy the wrapper and build files first, so dependency resolution is
# cached across builds unless build.gradle.kts/gradle.properties/the
# wrapper itself actually changed - a full `git diff` in application
# code shouldn't force re-downloading every dependency.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
# Belt-and-suspenders alongside the git-tracked executable bit on
# `gradlew` itself (a real, caught-in-CI bug this session: git on
# Windows doesn't reliably preserve the Unix executable bit, so a
# checkout on a Linux runner/image can silently lose it) - explicit
# here so a Docker build never depends on that bit surviving intact.
RUN chmod +x gradlew
RUN ./gradlew --version

COPY src ./src
RUN ./gradlew buildFatJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Runs as a non-root user - a container running as root is a real,
# avoidable privilege-escalation surface, not a hypothetical one.
RUN useradd --system --create-home --shell /usr/sbin/nologin fish
COPY --from=build /workspace/build/libs/fish-gl-engine-all.jar ./app.jar
USER fish

EXPOSE 8080

# FISH_DB_*/FISH_JWT_* are required environment variables (docs/DDD_Design.md
# Section 10/10.19) - supplied at deploy time (AWS Secrets Manager/ECS
# task definition env, or `docker run -e`), never baked into the image.
ENTRYPOINT ["java", "-jar", "app.jar"]
