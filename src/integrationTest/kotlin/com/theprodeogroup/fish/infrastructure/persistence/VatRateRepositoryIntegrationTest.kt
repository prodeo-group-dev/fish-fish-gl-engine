package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tax.TestVatRates
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatRateRow
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.insert
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Verifies [ExposedVatRateRepository] and `V30__vat_rates.sql` against a real
 * Postgres. Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class VatRateRepositoryIntegrationTest {

    private val repository = ExposedVatRateRepository()

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

    /** Resolves every category on a set of dates that straddle the one dated change, so the seed and the test-side copy cannot drift apart unnoticed. */
    private fun assertSameRates(actual: VatRateSchedule, expected: VatRateSchedule) {
        val dates = listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 1), LocalDate.of(2027, 1, 1))
        for (date in dates) {
            val a = actual.categoriesAsOf(date)
            val e = expected.categoriesAsOf(date)
            a.map { it.category } shouldBe e.map { it.category }
            a.map { it.rate?.stripTrailingZeros() } shouldBe e.map { it.rate?.stripTrailingZeros() }
        }
    }

    @Test
    fun `given a freshly migrated database, when the Ireland and UK schedules are read, then they resolve identically to the rates the tests assume`() {
        assertSameRates(repository.findVerifiedScheduleFor(Jurisdiction.IE)!!, TestVatRates.IRELAND)
        assertSameRates(repository.findVerifiedScheduleFor(Jurisdiction.UK)!!, TestVatRates.UK)
    }

    @Test
    fun `given the seeded Sierra Leone rate is unverified, when its schedule is read, then it is null - and flipping it to verified makes it live without any code change`() {
        repository.findVerifiedScheduleFor(Jurisdiction.SL) shouldBe null

        repository.save(VatRateRow(Jurisdiction.SL, VatCategory.STANDARD, BigDecimal("0.150000"), LocalDate.of(2000, 1, 1), verified = true))
        val live = repository.findVerifiedScheduleFor(Jurisdiction.SL)!!
        live.rateFor(VatCategory.STANDARD, LocalDate.of(2026, 10, 6)).stripTrailingZeros() shouldBe BigDecimal("0.15")

        // restore the seeded (unverified) state so the test is repeatable and does not leave SL live
        repository.save(VatRateRow(Jurisdiction.SL, VatCategory.STANDARD, BigDecimal("0.150000"), LocalDate.of(2000, 1, 1), verified = false))
        repository.findVerifiedScheduleFor(Jurisdiction.SL) shouldBe null
    }

    @Test
    fun `given a jurisdiction with no rows, when its schedule is read, then it is null`() {
        repository.findVerifiedScheduleFor(Jurisdiction.LR) shouldBe null
    }

    @Test
    fun `given a new country's rows are inserted as data, when read, then it has a schedule with no code change`() {
        val za = Jurisdiction("ZA")
        repository.save(VatRateRow(za, VatCategory.STANDARD, BigDecimal("0.150000"), LocalDate.of(2000, 1, 1), verified = true))
        repository.save(VatRateRow(za, VatCategory.ZERO_RATED, BigDecimal.ZERO, LocalDate.of(2000, 1, 1), verified = true))

        repository.findVerifiedScheduleFor(za)!!.categoriesAsOf(LocalDate.of(2026, 10, 6)).map { it.category } shouldBe
            listOf(VatCategory.STANDARD, VatCategory.ZERO_RATED, VatCategory.EXEMPT)
    }

    @Test
    fun `given a rate of 15 instead of 0-15 is inserted straight into the table, when it is written, then the database rejects it - the CHECK is the backstop behind the domain guard`() {
        val failure = runCatching {
            org.jetbrains.exposed.sql.transactions.transaction {
                VatRatesTable.insert { statement ->
                    statement[jurisdiction] = "ZB"
                    statement[category] = VatCategory.STANDARD.name
                    statement[rate] = BigDecimal("15")
                    statement[effectiveFrom] = LocalDate.of(2000, 1, 1)
                    statement[verified] = true
                }
            }
        }

        failure.isFailure shouldBe true
        repository.findVerifiedScheduleFor(Jurisdiction("ZB")) shouldBe null
    }
}
