plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "2.3.12"
    application
}

group = "com.theprodeogroup.fish"
version = "0.1.0"

repositories {
    mavenCentral()
}

application {
    // docs/DDD_Design.md Section 10.19 - the web/API layer's entry point.
    mainClass.set("com.theprodeogroup.fish.infrastructure.web.ApplicationKt")
}

val exposedVersion = "0.56.0"
val flywayVersion = "10.20.1"
val ktorVersion = "2.3.12"

// Separate source set for tests that need a live database - kept out of
// the default `test` task so `gradle test` stays hermetic (no external
// dependencies, safe for CI or any machine without Postgres running).
// Run explicitly with `gradle integrationTest`. See docs/DDD_Design.md
// Section 10.
sourceSets {
    main {
        // `common/` is the fish-common submodule (Money/ValidationResult) -
        // its Kotlin source is compiled directly into this project, not
        // consumed as a published artifact. See common/README.md.
        kotlin.srcDir("common/src/main/kotlin")
    }
    create("integrationTest") {
        kotlin.srcDir("src/integrationTest/kotlin")
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // Web/API layer (docs/DDD_Design.md Section 10.19)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("com.auth0:jwks-rsa:0.22.1")

    // Outbound HTTP client (docs/Tenancy_Administration_Extraction_DDD_Design.md
    // §2's "call EA over HTTP with the caller's forwarded bearer token") -
    // GL's first outbound service-to-service call; mirrors IM's own
    // GlEngineGateway client dependencies exactly (IM/build.gradle.kts).
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    // Structured (JSON) log output (docs/GL_Production_Readiness_Assessment.md
    // finding "no structured logging") - CloudWatch Logs Insights can query
    // individual fields (requestId, level, logger) directly once log lines
    // are JSON instead of plain text.
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // RecordAdminPhoneNumberUseCase's own check that Cognito genuinely
    // verified a phone number before GL's database records it as such
    // (never trusting the caller's own claim - see that use case's
    // KDoc). Credentials come from the ECS task role automatically
    // (DefaultCredentialsProvider's container-credentials step) -
    // nothing to configure here beyond the dependency itself.
    implementation(platform("software.amazon.awssdk:bom:2.29.11"))
    implementation("software.amazon.awssdk:cognitoidentityprovider")
    // SesStaffInviteNotificationGateway (2026-08-31) - GL's own first
    // outbound email, same BOM version already pinned above.
    implementation("software.amazon.awssdk:sesv2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Ktor's `buildFatJar` task depends on this `shadowJar` task (the
// Gradle Shadow plugin's own task) to actually build the combined jar.
// Discovered 2026-08-26, the first time this fat jar was ever
// actually run outside a test (doing a manual production deploy while
// CI was down): Flyway 10/11 discover their pluggable components
// (location resolvers, database-type handlers) via Java's ServiceLoader
// (registration files under META-INF/services). Shadow's DEFAULT merge behavior is
// last-one-wins for same-named files across dependency jars, not
// concatenation - so only ONE of flyway-core's/flyway-database-postgresql's
// several META-INF/services entries survived the merge, leaving
// Flyway's location-resolver registry completely empty at runtime
// (confirmed directly: Flyway 11 throws "Unknown prefix for location
// (should be one of ): classpath:db/callback" - an empty list of known
// prefixes - while Flyway 10 fails silently instead, treating every
// migration filename as unrecognised). `mergeServiceFiles()` is Shadow's
// standard, documented fix for exactly this class of fat-jar problem -
// it concatenates same-named META-INF/services files instead of
// clobbering them. Never caught by tests: `gradle test`/`integrationTest`
// both run Flyway via Gradle's own runtime classpath (many separate jars
// on disk), never through a single merged fat jar at all.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
}

tasks.register<Test>("integrationTest") {
    description = "Runs tests that need a live Postgres database (FISH_DB_* env vars required)."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}
