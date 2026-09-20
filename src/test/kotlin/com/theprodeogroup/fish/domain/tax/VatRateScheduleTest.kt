package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.common.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * `BigDecimal.equals()` is scale-sensitive - same reason `RateStructureTest`
 * needed this exact helper.
 */
private infix fun BigDecimal.shouldEqualNumerically(other: BigDecimal) {
    (this.compareTo(other) == 0) shouldBe true
}

/**
 * TDD proof of docs/IE/IE_VAT_MVP_Design.md's real Irish VAT rates and the
 * effective-dating decision needed for the 1 Jul 2026 SECOND_REDUCED
 * change (13.5% -> 9%) - real figures from docs/IE/IE_Tax_And_Currency_Settings.md,
 * not made-up examples.
 */
class VatRateScheduleTest {

    private val eur: Currency = Currency.getInstance("EUR")

    // --- rateFor: real Irish rates ---------------------------------------

    @Test
    fun `given the standard category, when rate resolved, then it is 23 percent`() {
        VatRateSchedule.IRELAND.rateFor(VatCategory.STANDARD, LocalDate.of(2026, 1, 1)) shouldEqualNumerically BigDecimal("0.23")
    }

    @Test
    fun `given the reduced category, when rate resolved, then it is 13-5 percent`() {
        VatRateSchedule.IRELAND.rateFor(VatCategory.REDUCED, LocalDate.of(2026, 1, 1)) shouldEqualNumerically BigDecimal("0.135")
    }

    @Test
    fun `given the super-reduced category, when rate resolved, then it is 4-8 percent`() {
        VatRateSchedule.IRELAND.rateFor(VatCategory.SUPER_REDUCED, LocalDate.of(2026, 1, 1)) shouldEqualNumerically BigDecimal("0.048")
    }

    @Test
    fun `given the zero-rated category, when rate resolved, then it is 0 percent`() {
        VatRateSchedule.IRELAND.rateFor(VatCategory.ZERO_RATED, LocalDate.of(2026, 1, 1)) shouldEqualNumerically BigDecimal.ZERO
    }

    // --- rateFor: effective-dating around the 1 Jul 2026 change ----------

    @Test
    fun `given second-reduced before 1 Jul 2026, when rate resolved, then it is the old 13-5 percent rate`() {
        VatRateSchedule.IRELAND.rateFor(VatCategory.SECOND_REDUCED, LocalDate.of(2026, 6, 30)) shouldEqualNumerically BigDecimal("0.135")
    }

    @Test
    fun `given second-reduced exactly on 1 Jul 2026, when rate resolved, then the new 9 percent rate already applies`() {
        VatRateSchedule.IRELAND.rateFor(VatCategory.SECOND_REDUCED, LocalDate.of(2026, 7, 1)) shouldEqualNumerically BigDecimal("0.09")
    }

    @Test
    fun `given second-reduced after 1 Jul 2026, when rate resolved, then it is the new 9 percent rate`() {
        VatRateSchedule.IRELAND.rateFor(VatCategory.SECOND_REDUCED, LocalDate.of(2026, 12, 31)) shouldEqualNumerically BigDecimal("0.09")
    }

    // --- rateFor: EXEMPT has no rate, by construction ---------------------

    @Test
    fun `given the exempt category, when rate resolved, then it fails rather than silently returning a rate`() {
        shouldThrow<IllegalArgumentException> {
            VatRateSchedule.IRELAND.rateFor(VatCategory.EXEMPT, LocalDate.of(2026, 1, 1))
        }
    }

    @Test
    fun `given a schedule constructed with an EXEMPT entry, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            VatRateSchedule(mapOf(VatCategory.EXEMPT to listOf(VatRateEntry(BigDecimal.ZERO, LocalDate.of(2000, 1, 1)))))
        }
    }

    // --- rateFor: no applicable entry / unconfigured category -------------

    @Test
    fun `given a date before the earliest configured rate, when rate resolved, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            VatRateSchedule.IRELAND.rateFor(VatCategory.STANDARD, LocalDate.of(1999, 12, 31))
        }
    }

    @Test
    fun `given a category with no configured entries at all, when rate resolved, then it fails`() {
        val partial = VatRateSchedule(
            mapOf(VatCategory.STANDARD to listOf(VatRateEntry(BigDecimal("0.23"), LocalDate.of(2000, 1, 1))))
        )

        shouldThrow<IllegalArgumentException> {
            partial.rateFor(VatCategory.REDUCED, LocalDate.of(2026, 1, 1))
        }
    }

    @Test
    fun `given a category configured with an empty entries list, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            VatRateSchedule(mapOf(VatCategory.STANDARD to emptyList()))
        }
    }

    @Test
    fun `given a negative rate, when creating a VatRateEntry, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            VatRateEntry(BigDecimal("-0.01"), LocalDate.of(2026, 1, 1))
        }
    }

    // --- vatAmountFor: rate resolution + Money multiplication combined ----

    @Test
    fun `given a standard-rated line, when VAT amount computed, then it is the net amount times 23 percent`() {
        val net = Money(BigDecimal("1000.00"), eur)

        VatRateSchedule.IRELAND.vatAmountFor(VatCategory.STANDARD, net, LocalDate.of(2026, 1, 1)) shouldBe Money(BigDecimal("230.00"), eur)
    }

    @Test
    fun `given an exempt line, when VAT amount computed, then it is zero without a rate lookup`() {
        val net = Money(BigDecimal("1000.00"), eur)

        VatRateSchedule.IRELAND.vatAmountFor(VatCategory.EXEMPT, net, LocalDate.of(2026, 1, 1)) shouldBe Money(BigDecimal.ZERO, eur)
    }

    @Test
    fun `given a zero-rated line, when VAT amount computed, then it is zero via the 0 percent rate`() {
        val net = Money(BigDecimal("1000.00"), eur)

        VatRateSchedule.IRELAND.vatAmountFor(VatCategory.ZERO_RATED, net, LocalDate.of(2026, 1, 1)) shouldBe Money(BigDecimal.ZERO, eur)
    }

    @Test
    fun `given a second-reduced line dated after the rate change, when VAT amount computed, then the new rate applies`() {
        val net = Money(BigDecimal("100.00"), eur)

        VatRateSchedule.IRELAND.vatAmountFor(VatCategory.SECOND_REDUCED, net, LocalDate.of(2026, 7, 15)) shouldBe Money(BigDecimal("9.00"), eur)
    }
}
