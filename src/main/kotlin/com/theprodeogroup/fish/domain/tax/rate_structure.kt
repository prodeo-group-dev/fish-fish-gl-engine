package com.theprodeogroup.fish.domain.tax

import java.math.BigDecimal

/**
 * How a [TaxRule]'s tax due is actually computed against a taxable
 * amount - generalizes the single flat `rate: BigDecimal` `TaxRule`
 * started with into the shapes real jurisdictions actually use for
 * Corporate Income Tax (`docs/UK/`, `IE/`, `NG/`, `SL/`, `LR/`, `GN/`,
 * `CI/` tax reference docs, 2026-08-22 - each closed a real "TaxRule's
 * flat rate can't represent this" gap flagged when those docs were
 * written). Confirmed scope before building: these five shapes cover
 * every Corporate-Income-Tax-equivalent computation found across all
 * seven jurisdictions; VAT/GST/TVA (no [TaxType] member yet) and
 * PAYE/ITS (`HR/`'s `PayrollTaxRule`, a different aggregate in a
 * different repo) are deliberately out of scope.
 *
 * - [Flat]: one rate, no conditions - Sierra Leone's 30%, the shape
 *   every `TaxRule` used before this file existed.
 * - [Tiered]: bands over the taxable amount. Two genuinely different
 *   mechanics live here depending on [Tiered.marginalRelief]: without
 *   it, standard portion-based progressive bands (kept general even
 *   though no jurisdiction in this codebase's reference docs currently
 *   needs it for Corporate Income Tax - PAYE/ITS-shaped scales are the
 *   natural future user, but that's `HR/`'s `PayrollTaxRule`, out of
 *   scope here); with it, the UK Corporation Tax shape specifically -
 *   NOT portion-based, it taxes the *whole* profit at the main rate then
 *   relieves a taper amount, a structurally different calculation.
 * - [CategorySplit]: rate depends on which category the taxpayer falls
 *   into, not on the amount - Ireland's trading/passive split, Liberia's
 *   general/mining-oil split, Guinea's standard/mining split, Côte
 *   d'Ivoire's resident/non-resident split. Four jurisdictions, one
 *   shape - "rate depends on a caller-supplied category" covers all of
 *   them despite the underlying business reasons being unrelated
 *   (income type vs. sector vs. residency).
 * - [ThresholdExemption]: full exemption if turnover/fixed-assets tests
 *   pass, otherwise delegates to another [RateStructure] - Nigeria's
 *   small-company exemption (turnover AND fixed-assets both under a
 *   cap). Wraps another [RateStructure] rather than only ever wrapping
 *   [Flat], since nothing about "is this taxpayer exempt" is tied to
 *   what happens when they're not.
 */
sealed class RateStructure {

    /**
     * Computes tax due on [taxableAmount] - the caller (`TaxComputation.of`)
     * is responsible for having already floored a loss to zero before
     * calling this; every variant here assumes a non-negative input.
     * [inputs] supplies whatever this shape needs (a category key,
     * turnover, fixed assets) - variants that don't need it ignore it.
     */
    abstract fun computeTaxDue(taxableAmount: BigDecimal, inputs: TaxComputationInputs = TaxComputationInputs.NONE): BigDecimal

    data class Flat(val rate: BigDecimal) : RateStructure() {
        init {
            require(rate.signum() >= 0) { "Flat rate cannot be negative" }
        }

        override fun computeTaxDue(taxableAmount: BigDecimal, inputs: TaxComputationInputs): BigDecimal =
            taxableAmount * rate
    }

    data class Tiered(
        val tiers: List<Tier>,
        val marginalRelief: MarginalRelief? = null
    ) : RateStructure() {
        init {
            require(tiers.isNotEmpty()) { "Tiered requires at least one tier" }
            require(tiers.dropLast(1).all { it.upperBound != null }) {
                "Only the last tier may have a null upperBound (no ceiling)"
            }
            if (marginalRelief != null) {
                require(tiers.size == 2) {
                    "marginalRelief requires exactly two tiers (small-profits rate, main rate) - ${tiers.size} given"
                }
            }
        }

        override fun computeTaxDue(taxableAmount: BigDecimal, inputs: TaxComputationInputs): BigDecimal =
            if (marginalRelief == null) computePortionBased(taxableAmount) else computeWithMarginalRelief(taxableAmount, marginalRelief)

        /** Standard progressive-band mechanics: each portion of [taxableAmount] falling in a tier is taxed at that tier's own rate. */
        private fun computePortionBased(taxableAmount: BigDecimal): BigDecimal {
            var remaining = taxableAmount
            var previousBound = BigDecimal.ZERO
            var total = BigDecimal.ZERO
            for (tier in tiers) {
                if (remaining.signum() <= 0) break
                val tierWidth = tier.upperBound?.let { it - previousBound }
                val amountInThisTier = if (tierWidth != null) remaining.min(tierWidth) else remaining
                total += amountInThisTier * tier.rate
                remaining -= amountInThisTier
                if (tier.upperBound != null) previousBound = tier.upperBound
            }
            return total
        }

        /**
         * The UK Corporation Tax shape: below [MarginalRelief.lowerLimit],
         * the whole amount is taxed at the small-profits rate (`tiers.first().rate`);
         * above [MarginalRelief.upperLimit], the whole amount is taxed at
         * the main rate (`tiers.last().rate`); in between, the whole
         * amount is taxed at the main rate and then relieved by
         * `(upperLimit - taxableAmount) * fraction` - NOT portion-based.
         */
        private fun computeWithMarginalRelief(taxableAmount: BigDecimal, relief: MarginalRelief): BigDecimal {
            val smallRate = tiers.first().rate
            val mainRate = tiers.last().rate
            return when {
                taxableAmount <= relief.lowerLimit -> taxableAmount * smallRate
                taxableAmount >= relief.upperLimit -> taxableAmount * mainRate
                else -> (taxableAmount * mainRate) - ((relief.upperLimit - taxableAmount) * relief.fraction)
            }
        }
    }

    data class CategorySplit(val ratesByCategory: Map<String, BigDecimal>) : RateStructure() {
        init {
            require(ratesByCategory.isNotEmpty()) { "CategorySplit requires at least one category" }
            require(ratesByCategory.keys.all { it.matches(CATEGORY_KEY_PATTERN) }) {
                "CategorySplit keys must match $CATEGORY_KEY_PATTERN (persistence-encoding constraint) - got ${ratesByCategory.keys}"
            }
            require(ratesByCategory.values.all { it.signum() >= 0 }) { "CategorySplit rates cannot be negative" }
        }

        override fun computeTaxDue(taxableAmount: BigDecimal, inputs: TaxComputationInputs): BigDecimal {
            val category = requireNotNull(inputs.category) {
                "CategorySplit requires TaxComputationInputs.category - known categories: ${ratesByCategory.keys}"
            }
            val rate = requireNotNull(ratesByCategory[category]) {
                "Unknown category '$category' - known categories: ${ratesByCategory.keys}"
            }
            return taxableAmount * rate
        }

        companion object {
            private val CATEGORY_KEY_PATTERN = Regex("[A-Za-z0-9_-]+")
        }
    }

    data class ThresholdExemption(
        val exemptionTest: ExemptionTest,
        val otherwise: RateStructure
    ) : RateStructure() {
        override fun computeTaxDue(taxableAmount: BigDecimal, inputs: TaxComputationInputs): BigDecimal =
            if (exemptionTest.isExempt(inputs)) BigDecimal.ZERO else otherwise.computeTaxDue(taxableAmount, inputs)
    }
}

/** One band of a [RateStructure.Tiered] schedule. [upperBound] `null` means no ceiling - only valid on the last tier in the list. */
data class Tier(val upperBound: BigDecimal?, val rate: BigDecimal) {
    init {
        require(rate.signum() >= 0) { "Tier rate cannot be negative" }
    }
}

/** The UK Corporation Tax marginal-relief parameters. [fraction] is the "standard fraction" (e.g. 3/200 = 0.015 for 2026/27). */
data class MarginalRelief(
    val lowerLimit: BigDecimal,
    val upperLimit: BigDecimal,
    val fraction: BigDecimal
) {
    init {
        require(lowerLimit < upperLimit) { "MarginalRelief lowerLimit must be less than upperLimit" }
        require(fraction.signum() > 0) { "MarginalRelief fraction must be positive" }
    }
}

/**
 * Nigeria's small-company exemption shape: exempt only if every
 * configured cap is met (both `null` caps count as "no constraint" -
 * requires at least one to avoid a vacuous exemption that's always
 * true). A caller who doesn't supply the corresponding
 * [TaxComputationInputs] field for a configured cap fails the test
 * (not exempt), never silently passes.
 */
data class ExemptionTest(
    val maxTurnover: BigDecimal? = null,
    val maxFixedAssets: BigDecimal? = null
) {
    init {
        require(maxTurnover != null || maxFixedAssets != null) {
            "ExemptionTest needs at least one of maxTurnover/maxFixedAssets"
        }
    }

    fun isExempt(inputs: TaxComputationInputs): Boolean {
        val turnoverOk = maxTurnover?.let { max -> (inputs.turnover ?: return false) <= max } ?: true
        val assetsOk = maxFixedAssets?.let { max -> (inputs.fixedAssets ?: return false) <= max } ?: true
        return turnoverOk && assetsOk
    }
}

/**
 * Caller-supplied classification data a [RateStructure] might need
 * beyond the taxable amount itself. Every field is optional since most
 * shapes ([RateStructure.Flat], plain [RateStructure.Tiered]) need none
 * of it - a [RateStructure] variant that does need a field throws its
 * own clear error if it's missing, rather than this type trying to
 * enforce per-shape requirements itself.
 */
data class TaxComputationInputs(
    val category: String? = null,
    val turnover: BigDecimal? = null,
    val fixedAssets: BigDecimal? = null
) {
    companion object {
        val NONE = TaxComputationInputs()
    }
}
