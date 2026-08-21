package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.common.Money
import java.math.BigDecimal
import java.util.Currency

/**
 * IAS 2.13's fixed/variable production overhead allocation rule
 * (docs/DDD_Design.md Section 2.6) - "a genuine new formula, not just
 * an Account tag," confirmed as the next real manufacturing step once
 * `ExpenseClassification`/`InventoryStage` existed. A pure calculation,
 * no aggregate identity or lifecycle - same "query/report layer" shape
 * as `TrialBalance`/`WorkingCapital`, just for a costing figure instead
 * of a balance-sheet one.
 *
 * **Fixed overhead** (factory depreciation, factory management salaries
 * - costs that don't vary with volume) is allocated at a rate based on
 * [normalCapacity] (the production expected on average over time under
 * normal circumstances), *not* [actualProduction] - IAS 2.13's whole
 * point: "the amount of fixed overhead allocated to each unit of
 * production is not increased as a consequence of low production or
 * idle plant." The unabsorbed portion ([unallocatedFixedOverhead]) is
 * recognised as a period expense, never capitalised into inventory
 * cost. Conversely, "in periods of abnormally high production, the
 * amount of fixed overhead allocated to each unit is decreased" -
 * achieved here by dividing over `max(normalCapacity, actualProduction)`
 * rather than [normalCapacity] alone, so [allocatedFixedOverhead] never
 * exceeds [fixedOverheadPool] itself even when actual production
 * exceeds normal capacity.
 *
 * **Variable overhead** (indirect materials, indirect labour - costs
 * that vary with volume) has no normal-capacity concept at all -
 * [allocatedVariableOverhead] is always the full [variableOverheadPool],
 * allocated by actual use of the production facilities per IAS 2.13.
 *
 * [totalAllocatedOverhead] is the figure a caller feeds into
 * `StockItem.addProductionCost()` - this type doesn't call it directly
 * (no `JournalEntry` posting here either, matching `StockItem`'s own
 * "never posts anything itself" precedent). Posting
 * [unallocatedFixedOverhead] as a period expense is likewise left to
 * the caller.
 */
class OverheadAllocation private constructor(
    val normalCapacity: BigDecimal,
    val actualProduction: BigDecimal,
    val fixedOverheadPool: Money,
    val variableOverheadPool: Money,
    val currency: Currency,
    val allocatedFixedOverhead: Money,
    val unallocatedFixedOverhead: Money,
    val allocatedVariableOverhead: Money
) {
    val totalAllocatedOverhead: Money
        get() = allocatedFixedOverhead + allocatedVariableOverhead

    companion object {
        fun calculate(
            normalCapacity: BigDecimal,
            actualProduction: BigDecimal,
            fixedOverheadPool: Money,
            variableOverheadPool: Money
        ): OverheadAllocation {
            require(normalCapacity.signum() > 0) { "Normal capacity must be positive" }
            require(actualProduction.signum() >= 0) { "Actual production cannot be negative" }
            require(fixedOverheadPool.amount.signum() >= 0) { "Fixed overhead pool cannot be negative" }
            require(variableOverheadPool.amount.signum() >= 0) { "Variable overhead pool cannot be negative" }
            require(fixedOverheadPool.currency == variableOverheadPool.currency) {
                "Fixed and variable overhead pools must share the same currency"
            }

            val allocationBase = if (actualProduction > normalCapacity) actualProduction else normalCapacity
            val allocatedFixedOverhead = fixedOverheadPool * actualProduction / allocationBase
            val unallocatedFixedOverhead = fixedOverheadPool - allocatedFixedOverhead
            val allocatedVariableOverhead = variableOverheadPool

            return OverheadAllocation(
                normalCapacity, actualProduction, fixedOverheadPool, variableOverheadPool,
                fixedOverheadPool.currency, allocatedFixedOverhead, unallocatedFixedOverhead, allocatedVariableOverhead
            )
        }
    }
}
