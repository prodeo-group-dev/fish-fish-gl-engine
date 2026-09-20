package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.common.Money
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One rate in force from [effectiveFrom] onward, until a later entry for
 * the same [VatCategory] supersedes it. Minimal effective-dating
 * (docs/IE/IE_VAT_MVP_Design.md Decision 5) - not a general rate-history
 * system, just enough to get the already-legislated 1 Jul 2026
 * `SECOND_REDUCED` change (13.5% -> 9%) right without a second build pass.
 */
data class VatRateEntry(val rate: BigDecimal, val effectiveFrom: LocalDate) {
    init {
        require(rate.signum() >= 0) { "VAT rate cannot be negative" }
    }
}

/**
 * A jurisdiction's VAT rate table, keyed by [VatCategory] - the line-level
 * counterpart to [TaxRule]/[RateStructure]'s taxpayer-level Corporate
 * Income Tax shapes. [EXEMPT] deliberately never appears as a key - it has
 * no rate by definition (see [VatCategory]'s own KDoc), so a schedule
 * that tried to configure one would be a modeling contradiction, rejected
 * at construction rather than silently accepted and never resolvable.
 */
class VatRateSchedule(private val entriesByCategory: Map<VatCategory, List<VatRateEntry>>) {
    init {
        require(VatCategory.EXEMPT !in entriesByCategory) {
            "EXEMPT has no rate by definition - it must not appear in a VatRateSchedule"
        }
        entriesByCategory.forEach { (category, entries) ->
            require(entries.isNotEmpty()) { "$category must have at least one rate entry" }
        }
    }

    /**
     * Resolves the rate in force for [category] as of [asOf] - the most
     * recent [VatRateEntry.effectiveFrom] not after [asOf]. Always fails
     * for [VatCategory.EXEMPT] - callers must branch on that category
     * before ever reaching a rate lookup, not rely on this returning a
     * value for it (see [vatAmountFor], which does that branching once,
     * centrally).
     */
    fun rateFor(category: VatCategory, asOf: LocalDate): BigDecimal {
        val entries = entriesByCategory[category]
            ?: throw IllegalArgumentException("No VAT rate schedule configured for $category")
        return entries.filter { !it.effectiveFrom.isAfter(asOf) }
            .maxByOrNull { it.effectiveFrom }
            ?.rate
            ?: throw IllegalArgumentException(
                "No $category VAT rate effective as of $asOf (earliest entry starts ${entries.minOf { it.effectiveFrom }})"
            )
    }

    /**
     * Combines rate resolution with the actual multiplication against
     * [netAmount] - the VAT-specific counterpart to
     * [RateStructure.computeTaxDue], kept as one call so a caller (the
     * eventual `RecordSaleUseCase`/`RecordVendorObligationUseCase`
     * reshape) never has to special-case [VatCategory.EXEMPT] itself;
     * this is the one place that branch lives.
     */
    fun vatAmountFor(category: VatCategory, netAmount: Money, asOf: LocalDate): Money =
        if (category == VatCategory.EXEMPT) {
            Money(BigDecimal.ZERO, netAmount.currency)
        } else {
            netAmount * rateFor(category, asOf)
        }

    companion object {
        /**
         * Real Irish rates (docs/IE/IE_Tax_And_Currency_Settings.md) - not
         * fictional figures. `2000-01-01` is a placeholder "always in
         * force" start date for rates with no known change date in this
         * project's research; only `SECOND_REDUCED` has a real, dated
         * change (1 Jul 2026, already legislated).
         */
        val IRELAND: VatRateSchedule = VatRateSchedule(
            mapOf(
                VatCategory.STANDARD to listOf(
                    VatRateEntry(BigDecimal("0.23"), LocalDate.of(2000, 1, 1))
                ),
                VatCategory.REDUCED to listOf(
                    VatRateEntry(BigDecimal("0.135"), LocalDate.of(2000, 1, 1))
                ),
                VatCategory.SECOND_REDUCED to listOf(
                    VatRateEntry(BigDecimal("0.135"), LocalDate.of(2000, 1, 1)),
                    VatRateEntry(BigDecimal("0.09"), LocalDate.of(2026, 7, 1))
                ),
                VatCategory.SUPER_REDUCED to listOf(
                    VatRateEntry(BigDecimal("0.048"), LocalDate.of(2000, 1, 1))
                ),
                VatCategory.ZERO_RATED to listOf(
                    VatRateEntry(BigDecimal.ZERO, LocalDate.of(2000, 1, 1))
                )
            )
        )
    }
}
