package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.common.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * The "Schedule of Inventory" - a standard supporting report listing
 * every [StockItem] a Company holds, the figure the Balance Sheet's
 * Inventory line is drawn from (docs/DDD_Design.md Section 2.6/3.1).
 * Added 2026-08-29 for the SOP dashboard tab, alongside [SalesTab]'s
 * item picker - the same underlying [StockItemRepository] data, viewed
 * as a report rather than a picklist.
 *
 * A pure projection of already-computed [StockItem] state ([StockItem.totalValue]/
 * [StockItem.totalCarryingValue] already exist per item) - no new
 * domain invariants, same "query/report layer" reasoning as
 * `AccountsReceivableAging`/`TrialBalance`, just simpler: nothing here
 * needs deriving from posted `JournalEntry` lines, since `StockItem`
 * already carries quantity/cost/NRV write-down directly.
 *
 * [totalCost] is the pure historical weighted-average figure
 * ([StockItem.totalValue] summed); [totalCarryingValue] is the lower-
 * of-cost-and-NRV figure the Balance Sheet actually needs - kept
 * separate the same way `StockItem` itself keeps them separate.
 */
class InventorySchedule private constructor(
    val asOfDate: LocalDate,
    val currency: Currency,
    val lines: List<InventoryScheduleLine>
) {
    val totalCost: Money
        get() = lines.fold(Money(BigDecimal.ZERO, currency)) { sum, line -> sum + line.totalValue }

    val totalCarryingValue: Money
        get() = lines.fold(Money(BigDecimal.ZERO, currency)) { sum, line -> sum + line.totalCarryingValue }

    companion object {
        fun of(stockItems: List<StockItem>, asOfDate: LocalDate, currency: Currency): InventorySchedule =
            InventorySchedule(
                asOfDate, currency,
                stockItems.map {
                    InventoryScheduleLine(
                        it.id, it.name, it.stage, it.quantityOnHand, it.unitCost, it.totalValue,
                        it.nrvWriteDownPerUnit, it.carryingValuePerUnit, it.totalCarryingValue
                    )
                }
            )
    }
}

data class InventoryScheduleLine(
    val stockItemId: StockItemId,
    val name: String,
    val stage: InventoryStage,
    val quantityOnHand: BigDecimal,
    val unitCost: Money,
    val totalValue: Money,
    val nrvWriteDownPerUnit: Money,
    val carryingValuePerUnit: Money,
    val totalCarryingValue: Money
)
