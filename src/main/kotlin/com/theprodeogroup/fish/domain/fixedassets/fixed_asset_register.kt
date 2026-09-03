package com.theprodeogroup.fish.domain.fixedassets

import com.theprodeogroup.common.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/** One row of a [FixedAssetRegister] - the per-asset detail a Fixed Asset Register report shows. */
data class FixedAssetRegisterLine(
    val id: FixedAssetId,
    val name: String,
    val category: AssetCategory,
    val cost: Money,
    val acquisitionDate: LocalDate,
    val usefulLifeYears: Int?,
    val accumulatedDepreciation: Money,
    val accumulatedImpairmentLoss: Money,
    val netBookValue: Money,
    val carryingAmount: Money,
    val isDisposed: Boolean
)

/**
 * The Fixed Asset Register report - every [FixedAsset] a Company holds
 * (docs/DDD_Design.md Section 2.8), same "derived from already-persisted
 * aggregates, no table of its own" treatment as `BalanceSheet`/
 * `AccountsPayableAging`. [lines] includes disposed assets too, for
 * audit-trail visibility - but the roll-up totals are struck only over
 * still-held (`!isDisposed`) assets, since `dispose()` never zeroes a
 * disposed asset's own [FixedAsset.cost]/[FixedAsset.accumulatedDepreciation]
 * fields (it only marks it disposed and posts the removal journal entry),
 * so a disposed asset's [FixedAsset.netBookValue] no longer reflects
 * anything actually on the books.
 */
data class FixedAssetRegister(
    val currency: Currency,
    val lines: List<FixedAssetRegisterLine>,
    val totalCost: Money,
    val totalAccumulatedDepreciation: Money,
    val totalAccumulatedImpairmentLoss: Money,
    val totalNetBookValue: Money,
    val totalCarryingAmount: Money
) {
    companion object {
        fun of(assets: List<FixedAsset>, currency: Currency): FixedAssetRegister {
            val zero = Money(BigDecimal.ZERO, currency)
            val lines = assets
                .sortedBy { it.acquisitionDate }
                .map {
                    FixedAssetRegisterLine(
                        it.id, it.name, it.category, it.cost, it.acquisitionDate, it.usefulLifeYears,
                        it.accumulatedDepreciation, it.accumulatedImpairmentLoss, it.netBookValue, it.carryingAmount, it.isDisposed
                    )
                }
            val held = lines.filterNot { it.isDisposed }
            return FixedAssetRegister(
                currency = currency,
                lines = lines,
                totalCost = held.fold(zero) { sum, line -> sum + line.cost },
                totalAccumulatedDepreciation = held.fold(zero) { sum, line -> sum + line.accumulatedDepreciation },
                totalAccumulatedImpairmentLoss = held.fold(zero) { sum, line -> sum + line.accumulatedImpairmentLoss },
                totalNetBookValue = held.fold(zero) { sum, line -> sum + line.netBookValue },
                totalCarryingAmount = held.fold(zero) { sum, line -> sum + line.carryingAmount }
            )
        }
    }
}
