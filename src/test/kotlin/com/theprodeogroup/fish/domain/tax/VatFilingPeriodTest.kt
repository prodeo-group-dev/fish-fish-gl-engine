package com.theprodeogroup.fish.domain.tax

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * TDD proof of docs/IE/IE_VAT_MVP_Design.md's own call to model a VAT
 * filing period as "a plain two-calendar-month date range," deliberately
 * independent of `PeriodType` (no bi-monthly value exists there - only
 * MONTH/QUARTER/YEAR/CUSTOM) since a Company's accounting-period cadence
 * and its VAT filing cadence are two genuinely independent things.
 */
class VatFilingPeriodTest {

    @Test
    fun `given a valid bi-monthly range, when created, then it holds the dates as given`() {
        val period = VatFilingPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28))

        period.startDate shouldBe LocalDate.of(2026, 1, 1)
        period.endDate shouldBe LocalDate.of(2026, 2, 28)
    }

    @Test
    fun `given startDate after endDate, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            VatFilingPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 1, 1))
        }
    }

    @Test
    fun `given the same start and end date, when created, then it succeeds - a one-day filing period is valid`() {
        val period = VatFilingPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1))

        period.startDate shouldBe period.endDate
    }

    @Test
    fun `given a date inside the range, when checked, then it is contained`() {
        val period = VatFilingPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28))

        (LocalDate.of(2026, 1, 15) in period) shouldBe true
        (LocalDate.of(2026, 1, 1) in period) shouldBe true
        (LocalDate.of(2026, 2, 28) in period) shouldBe true
    }

    @Test
    fun `given a date outside the range, when checked, then it is not contained`() {
        val period = VatFilingPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28))

        (LocalDate.of(2025, 12, 31) in period) shouldBe false
        (LocalDate.of(2026, 3, 1) in period) shouldBe false
    }
}
