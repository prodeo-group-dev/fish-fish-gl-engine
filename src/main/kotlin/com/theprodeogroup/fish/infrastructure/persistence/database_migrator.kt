package com.theprodeogroup.fish.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import javax.sql.DataSource

/**
 * Thin wrapper over Flyway (docs/DDD_Design.md Section 10). Migrations
 * live under `src/main/resources/db/migration`, standard Flyway naming
 * (`V1__description.sql`, `V2__description.sql`, ...).
 *
 * No domain-table migrations exist yet - `V1__baseline.sql` only proves
 * the pipeline (Gradle -> Flyway -> Postgres) works end to end. Real
 * schema lands incrementally as repository implementations get built,
 * not as one big upfront migration.
 */
object DatabaseMigrator {
    fun migrate(dataSource: DataSource): MigrateResult {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
        return flyway.migrate()
    }
}
