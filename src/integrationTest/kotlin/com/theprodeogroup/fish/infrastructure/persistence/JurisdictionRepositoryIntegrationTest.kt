package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JurisdictionEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies [ExposedJurisdictionRepository] and `V29__jurisdictions.sql`
 * against a real Postgres, same discipline as the other integration
 * tests. Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class JurisdictionRepositoryIntegrationTest {

    private val repository = ExposedJurisdictionRepository()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    @Test
    fun `given a freshly migrated database, when enabled jurisdictions are listed, then the seven originals are there and UK includes Northern Ireland`() {
        val codes = repository.findAllEnabled().map { it.code.code }

        codes.containsAll(listOf("UK", "IE", "NG", "SL", "LR", "GN", "CI")) shouldBe true
        codes.contains("GB") shouldBe false
        repository.findEnabledByCode(Jurisdiction.UK)?.name shouldBe "United Kingdom (including Northern Ireland)"
        repository.findEnabledByCode(Jurisdiction.CI)?.name shouldBe "Côte d'Ivoire"
    }

    @Test
    fun `given a new jurisdiction is saved as data, when looked up, then it is found with no code change - and disabling it hides it again`() {
        val za = Jurisdiction("ZA")

        repository.save(JurisdictionEntry(za, "South Africa", enabled = true))
        repository.findEnabledByCode(za)?.name shouldBe "South Africa"
        repository.findAllEnabled().map { it.code }.contains(za) shouldBe true

        repository.save(JurisdictionEntry(za, "South Africa", enabled = false))
        repository.findEnabledByCode(za) shouldBe null
        repository.findAllEnabled().map { it.code }.contains(za) shouldBe false
    }

    @Test
    fun `given a code that was never registered, when looked up, then it is null`() {
        repository.findEnabledByCode(Jurisdiction("QQ")) shouldBe null
    }
}
