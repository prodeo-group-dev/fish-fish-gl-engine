package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.Jurisdiction
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
     * eventual `RecordSaleUseCase`/`RecordSupplierObligationUseCase`
     * reshape) never has to special-case [VatCategory.EXEMPT] itself;
     * this is the one place that branch lives.
     */
    fun vatAmountFor(category: VatCategory, netAmount: Money, asOf: LocalDate): Money =
        if (category == VatCategory.EXEMPT) {
            Money(BigDecimal.ZERO, netAmount.currency)
        } else {
            netAmount * rateFor(category, asOf)
        }

    /**
     * Whether [category] has a resolvable rate on this schedule -
     * [VatCategory.EXEMPT] always does (by construction, no entry
     * needed), any other category only if it has at least one
     * [VatRateEntry]. Lets a caller validate a line's category
     * *before* attempting [vatAmountFor]/[rateFor], turning "this
     * jurisdiction's VAT law has no such band" (e.g. `SECOND_REDUCED`
     * against [UK], which genuinely has no such rate) into a normal
     * validation failure a use case can return as a `Result`, rather
     * than an uncaught [IllegalArgumentException] from [rateFor] -
     * this codebase's own stated convention
     * (`ValidationResult`/sealed `Result` over throwing for expected
     * domain-rule violations, not a programmer-error guard).
     */
    fun supports(category: VatCategory): Boolean =
        category == VatCategory.EXEMPT || category in entriesByCategory


    /**
     * The categories this schedule has a resolvable rate for as of
     * [asOf], in [VatCategory] declaration order - what a Company's Sales
     * form may offer (2026-10-06, `GET /companies/{companyId}/vat-categories`).
     * [VatCategory.EXEMPT] is always present with a `null` rate (outside
     * VAT scope, not a 0% rate); a category whose earliest rate starts
     * after [asOf] is omitted rather than failing.
     */
    fun categoriesAsOf(asOf: LocalDate): List<VatCategoryRate> =
        VatCategory.entries.mapNotNull { category ->
            when {
                category == VatCategory.EXEMPT -> VatCategoryRate(category, null)
                category !in entriesByCategory -> null
                else -> try {
                    VatCategoryRate(category, rateFor(category, asOf))
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }

    companion object {
        /**
         * Builds a schedule from stored rows (2026-10-06, Option B: VAT rates
         * are DATA in `vat_rates`, not code constants - a new country or a
         * rate change is an INSERT). Returns `null` for no rows: a
         * jurisdiction with no (verified) rates has no schedule, never a
         * fallback to another jurisdiction's. Callers pass only the rows they
         * are entitled to use (`VatRateRepository` filters on
         * [VatRateRow.verified]).
         */
        fun of(rows: List<VatRateRow>): VatRateSchedule? {
            if (rows.isEmpty()) return null
            return VatRateSchedule(
                rows.groupBy { it.category }.mapValues { (_, categoryRows) ->
                    categoryRows.map { VatRateEntry(it.rate, it.effectiveFrom) }
                }
            )
        }
    }
}

/** One category's rate in a [VatRateSchedule.categoriesAsOf] answer; [rate] is a fraction (0.23 = 23%) and `null` only for [VatCategory.EXEMPT]. */
data class VatCategoryRate(val category: VatCategory, val rate: BigDecimal?)

/**
 * One stored row of the `vat_rates` table. [verified] is the governance
 * gate (2026-10-06, Femi: SL is to be seeded but "not live until
 * confirmed"): only verified rows form a usable schedule, so seeding a
 * jurisdiction's rates ahead of checking them against a primary tax
 * source posts nothing. EXEMPT has no rate by definition and is never
 * stored.
 */
data class VatRateRow(
    val jurisdiction: Jurisdiction,
    val category: VatCategory,
    val rate: BigDecimal,
    val effectiveFrom: LocalDate,
    val verified: Boolean
) {
    init {
        require(category != VatCategory.EXEMPT) { "EXEMPT has no rate by definition - it must not be stored" }
        require(rate.signum() >= 0) { "VAT rate cannot be negative" }
    }
}

/**
 * The VAT rate table. Deliberately small: [save] for seeding/the future
 * operator write path, and the one read every posting and the
 * categories route make.
 */
interface VatRateRepository {
    fun save(row: VatRateRow)

    /** The schedule for [jurisdiction] built from its VERIFIED rows only, or `null` if it has none. */
    fun findVerifiedScheduleFor(jurisdiction: Jurisdiction): VatRateSchedule?
}
