package com.theprodeogroup.fish.infrastructure.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the Gradle -> Flyway -> Postgres pipeline actually works
 * against a live database - deliberately kept out of the default
 * `test` task (docs/DDD_Design.md Section 10), since it needs
 * `FISH_DB_USER`/`FISH_DB_PASSWORD` set and a reachable Postgres
 * instance, neither of which every environment running `gradle test`
 * has. Run explicitly with `gradle integrationTest`.
 *
 * Skips (not fails) if the required environment variables aren't set,
 * so `gradle integrationTest` doesn't hard-fail for a fresh checkout
 * with no local database configured yet.
 */
class DatabaseMigratorIntegrationTest {

    @Test
    fun `given a live Postgres database, when migrated, then Flyway succeeds`() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping (see docs/DDD_Design.md Section 10 for local setup)"
        )

        val dataSource = DatabaseConfig.dataSource()
        val result = DatabaseMigrator.migrate(dataSource)

        result.success shouldBe true
    }
}
