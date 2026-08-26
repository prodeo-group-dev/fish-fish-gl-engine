package com.theprodeogroup.fish.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import javax.sql.DataSource

/**
 * Thin wrapper over Flyway (docs/DDD_Design.md Section 10). Migrations
 * live under `src/main/resources/db/migration`, standard Flyway naming
 * (`V1__description.sql`, `V2__description.sql`, ...).
 *
 * No domain-table migrations exist yet beyond what's shipped - `V1__baseline.sql`
 * only proves the pipeline (Gradle -> Flyway -> Postgres) works end to
 * end. Real schema lands incrementally as repository implementations
 * get built, not as one big upfront migration.
 *
 * **Fails loudly if zero migrations resolve from `classpath:db/migration`
 * at all**, regardless of *why* - a real, previously-latent bug landed
 * in production 2026-08-26 (the first time this fat jar was ever
 * actually run outside a test) that hinged entirely on `migrate()`'s
 * result never being checked: `build.gradle.kts`'s `shadowJar` task was
 * silently clobbering same-named ServiceLoader registration files
 * (under META-INF/services) instead of merging them across dependency
 * jars (Shadow's default behavior, fixed there with `mergeServiceFiles()`)
 * - leaving Flyway's ServiceLoader-based plugin registry empty, so it
 * resolved zero migrations from the fat jar. Flyway itself considered
 * "found 0 valid migrations, nothing to apply" a completely successful
 * outcome, so the app started up normally and served requests against
 * an empty schema with no error anywhere - `flyway.migrate().success`
 * alone would NOT have caught this, it's true either way. Never caught
 * by tests: `gradle test`/`integrationTest` both run Flyway via
 * Gradle's own runtime classpath (many separate jars on disk), never
 * through a fat jar at all.
 */
object DatabaseMigrator {
    fun migrate(dataSource: DataSource): MigrateResult {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
        val resolvedCount = flyway.info().all().size
        check(resolvedCount > 0) {
            "Flyway resolved zero migrations from classpath:db/migration - expected at least V1__baseline.sql. " +
                "See this object's KDoc for the 2026-08-26 shadowJar META-INF/services bug this guards against."
        }
        return flyway.migrate()
    }
}
