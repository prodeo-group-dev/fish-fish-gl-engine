package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.common.ValidationResult
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * A stock-keeping unit tracked by quantity and weighted-average unit cost
 * (docs/DDD_Design.md Section 2.6) - Increment 3 of the ecosystem, built
 * standalone: no live linkage to PurchaseOrder/SalesOrder lines yet
 * (deliberately deferred, matching Increments 1-2's discipline).
 *
 * Weighted-average costing: every receipt blends its cost into the
 * existing unit cost, weighted by quantity. Issues reduce quantity but
 * never change unit cost - only a receipt can move the average.
 *
 * [stage] (`InventoryStage`, added 2026-08-14) places this item in a
 * manufacturing cost flow - Raw Material, Work in Progress, or Finished
 * Goods. Defaults to [InventoryStage.FINISHED_GOODS], matching every
 * `StockItem` built before this existed (a pure-trading company that
 * buys and resells has no reason to ever pick a different stage).
 * [consumeInto]/[addProductionCost]/[completeInto] are the WIP cost-flow
 * methods - IAS 2's "cost accumulated to date" for Work in Progress.
 * Like [recordReceipt]/[recordIssue], **none of these post a
 * `JournalEntry`** - that stays a caller concern, matching how
 * `PurchaseOrder.send()`/`SalesOrder.deliverLine()` call `StockItem`'s
 * methods and construct their own `JournalLine`s separately. No
 * `ProductionOrder`/job-costing aggregate exists to orchestrate this -
 * deliberately out of scope, a caller must call these methods directly
 * in the right sequence.
 *
 * [assessNetRealisableValue] (added 2026-08-14) is IAS 2.9's "lower of
 * cost and net realisable value" measurement rule, the last real gap
 * left from the original Inventory IFRS grounding. [nrvWriteDownPerUnit]
 * is a *separate* contra-value, same relationship `FixedAsset.accumulatedDepreciation`
 * has to `cost` - [unitCost]/[totalValue] stay pure historical
 * weighted-average cost, unaffected, still the correct basis for the
 * next [recordReceipt]'s blend; [carryingValuePerUnit]/[totalCarryingValue]
 * are the derived lower-of-cost-and-NRV figures a Balance Sheet actually
 * needs. Tracked per-unit rather than as a running total specifically so
 * [recordIssue] never needs to touch it - exactly the same reason
 * [unitCost] itself is per-unit, not a total.
 */
class StockItem private constructor(
    val id: StockItemId,
    val companyId: CompanyId,
    val name: String,
    val currency: Currency,
    val stage: InventoryStage
) {
    var quantityOnHand: BigDecimal = BigDecimal.ZERO
        private set

    var unitCost: Money = Money(BigDecimal.ZERO, currency)
        private set

    val totalValue: Money
        get() = unitCost * quantityOnHand

    var nrvWriteDownPerUnit: Money = Money(BigDecimal.ZERO, currency)
        private set

    /** Cost less any NRV write-down - IAS 2.9's "lower of cost and net realisable value," per unit. */
    val carryingValuePerUnit: Money
        get() = unitCost - nrvWriteDownPerUnit

    /** [carryingValuePerUnit] times [quantityOnHand] - the Balance Sheet figure, distinct from the pure-cost [totalValue]. */
    val totalCarryingValue: Money
        get() = carryingValuePerUnit * quantityOnHand

    /**
     * Blends [quantityReceived] at [costReceived] into the running
     * weighted-average unit cost: newUnitCost = (existingQty*existingCost
     * + receivedQty*receivedCost) / (existingQty + receivedQty).
     */
    fun recordReceipt(quantityReceived: BigDecimal, costReceived: Money): ValidationResult {
        if (quantityReceived.signum() <= 0) {
            return ValidationResult.failure("Received quantity must be positive")
        }
        if (costReceived.currency != currency) {
            return ValidationResult.failure("Received cost currency must match this StockItem's currency")
        }

        val existingValue = unitCost * quantityOnHand
        val receivedValue = costReceived * quantityReceived
        val newQuantity = quantityOnHand + quantityReceived

        unitCost = (existingValue + receivedValue) / newQuantity
        quantityOnHand = newQuantity
        return ValidationResult.success()
    }

    /** Reduces quantity on hand. Unit cost is unchanged - only receipts move the weighted average. */
    fun recordIssue(quantityIssued: BigDecimal): ValidationResult {
        if (quantityIssued.signum() <= 0) {
            return ValidationResult.failure("Issued quantity must be positive")
        }
        if (quantityIssued > quantityOnHand) {
            return ValidationResult.failure("Cannot issue more than the quantity on hand")
        }

        quantityOnHand -= quantityIssued
        return ValidationResult.success()
    }

    /**
     * Consumes [quantityConsumed] of this Raw Material item into
     * [workInProgress]'s accumulated cost - Direct Materials, the first
     * leg of IAS 2's manufacturing cost build-up. Requires
     * `this.stage == RAW_MATERIAL` and `workInProgress.stage == WORK_IN_PROGRESS`.
     *
     * No unit-of-measure conversion is modeled - [quantityConsumed] is
     * assumed to be in whatever unit both items track uniformly, the
     * same simplification `recordReceipt`/`recordIssue` already make.
     */
    fun consumeInto(workInProgress: StockItem, quantityConsumed: BigDecimal): ValidationResult =
        transferTo(
            destination = workInProgress,
            quantity = quantityConsumed,
            expectedSourceStage = InventoryStage.RAW_MATERIAL,
            expectedDestinationStage = InventoryStage.WORK_IN_PROGRESS,
            sourceStageLabel = "Raw Material",
            destinationStageLabel = "Work in Progress"
        )

    /**
     * Adds [cost] (labour or factory overhead) directly to this Work in
     * Progress item's accumulated cost - unlike [consumeInto], this
     * isn't a receipt of new units, it's cost incurred against units
     * already in production. [quantityOnHand] is unchanged; [unitCost]
     * rises (same units, more cost incurred against them) - exactly
     * IAS 2's "WIP measured at cost accumulated to date."
     *
     * Requires `stage == WORK_IN_PROGRESS` and a positive [quantityOnHand]
     * to spread the cost across (there's nothing in production to
     * accumulate cost against otherwise).
     */
    fun addProductionCost(cost: Money): ValidationResult {
        if (stage != InventoryStage.WORK_IN_PROGRESS) {
            return ValidationResult.failure("Only Work in Progress stock can accumulate production cost directly")
        }
        if (cost.amount.signum() <= 0) {
            return ValidationResult.failure("Production cost must be positive")
        }
        if (cost.currency != currency) {
            return ValidationResult.failure("Production cost currency must match this StockItem's currency")
        }
        if (quantityOnHand.signum() <= 0) {
            return ValidationResult.failure("Cannot add production cost to Work in Progress with no quantity")
        }

        val existingValue = unitCost * quantityOnHand
        unitCost = (existingValue + cost) / quantityOnHand
        return ValidationResult.success()
    }

    /**
     * Completes [quantityCompleted] units of production, moving them
     * (and their accumulated cost) from this Work in Progress item into
     * [finishedGoods] - IAS 2's Cost of Production, the point Work in
     * Progress cost transfers out of the Manufacturing Account.
     * Requires `this.stage == WORK_IN_PROGRESS` and
     * `finishedGoods.stage == FINISHED_GOODS`.
     */
    fun completeInto(finishedGoods: StockItem, quantityCompleted: BigDecimal): ValidationResult =
        transferTo(
            destination = finishedGoods,
            quantity = quantityCompleted,
            expectedSourceStage = InventoryStage.WORK_IN_PROGRESS,
            expectedDestinationStage = InventoryStage.FINISHED_GOODS,
            sourceStageLabel = "Work in Progress",
            destinationStageLabel = "Finished Goods"
        )

    /**
     * Re-assesses the lower-of-cost-and-NRV write-down to
     * [netRealisableValuePerUnit] - IAS 2.9's core measurement rule.
     * If NRV falls below [unitCost], the shortfall (`unitCost -
     * netRealisableValuePerUnit`) becomes the target per-unit
     * write-down; if NRV is at or above [unitCost], the target is
     * zero - inventory is never written *up* above its own cost (IAS
     * 2.34's reversal cap), it simply isn't written down at all.
     *
     * Deliberately mirrors `Customer.assessExpectedCreditLoss()`'s
     * target-and-delta shape (re-assess to a target each call, post
     * only the difference) rather than `FixedAsset.recordDepreciation()`'s
     * pure accumulation - IAS 2.34 explicitly requires reversals when
     * NRV recovers, capped so the reversal never exceeds the original
     * write-down (the carrying amount never exceeds cost). A top-up
     * debits [writeDownExpenseAccountId]/credits [inventoryAssetAccountId];
     * a reversal does the opposite, per IAS 2.34's requirement that a
     * reversal reduce the period's inventory expense rather than create
     * separate income. `JournalSource.SYSTEM`, matching the same
     * automated-period-end-review precedent.
     *
     * Returns `null` if there's no [quantityOnHand] to carry a value at
     * all, or if the delta is exactly zero - nothing to post.
     */
    fun assessNetRealisableValue(
        netRealisableValuePerUnit: Money,
        writeDownExpenseAccountId: AccountId,
        inventoryAssetAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (quantityOnHand.signum() <= 0) return null
        if (netRealisableValuePerUnit.currency != currency) return null

        val zero = Money(BigDecimal.ZERO, currency)
        val targetPerUnit = if (netRealisableValuePerUnit < unitCost) unitCost - netRealisableValuePerUnit else zero
        val delta = (targetPerUnit - nrvWriteDownPerUnit) * quantityOnHand
        if (delta.amount.signum() == 0) return null

        val lines = if (delta.amount.signum() > 0) {
            listOf(
                JournalLine(writeDownExpenseAccountId, delta, TransactionSide.DEBIT),
                JournalLine(inventoryAssetAccountId, delta, TransactionSide.CREDIT)
            )
        } else {
            val reversalAmount = zero - delta
            listOf(
                JournalLine(inventoryAssetAccountId, reversalAmount, TransactionSide.DEBIT),
                JournalLine(writeDownExpenseAccountId, reversalAmount, TransactionSide.CREDIT)
            )
        }

        nrvWriteDownPerUnit = targetPerUnit
        return JournalEntry.create(
            periodId, date, lines, JournalSource.SYSTEM,
            "Net realisable value assessment - $name ($id)", journalEntryId
        )
    }

    /**
     * Shared shape behind [consumeInto]/[completeInto] - both are
     * "reduce this item by [quantity], fold that quantity and its
     * [unitCost] into [destination] via the same weighted-average blend
     * `recordReceipt` already does." The two public methods differ only
     * in which stage transition they enforce.
     *
     * Checks stages and currency match *before* mutating anything -
     * [recordIssue] must not succeed on `this` if the corresponding
     * [StockItem.recordReceipt] on [destination] would then fail on a
     * currency mismatch, which would leave [this] short a quantity that
     * never actually arrived anywhere.
     */
    private fun transferTo(
        destination: StockItem,
        quantity: BigDecimal,
        expectedSourceStage: InventoryStage,
        expectedDestinationStage: InventoryStage,
        sourceStageLabel: String,
        destinationStageLabel: String
    ): ValidationResult {
        if (stage != expectedSourceStage) {
            return ValidationResult.failure("Only $sourceStageLabel stock can be transferred into $destinationStageLabel")
        }
        if (destination.stage != expectedDestinationStage) {
            return ValidationResult.failure("Target must be a $destinationStageLabel StockItem")
        }
        if (destination.currency != currency) {
            return ValidationResult.failure("$sourceStageLabel and $destinationStageLabel StockItems must share the same currency")
        }

        val issueResult = recordIssue(quantity)
        if (!issueResult.isValid) {
            return issueResult
        }
        return destination.recordReceipt(quantity, unitCost)
    }

    companion object {
        fun create(
            companyId: CompanyId,
            name: String,
            currency: Currency,
            stage: InventoryStage = InventoryStage.FINISHED_GOODS,
            id: StockItemId = StockItemId.generate()
        ): StockItem = StockItem(id, companyId, name, currency, stage)

        /**
         * Rebuilds an already-valid StockItem from persisted state
         * (docs/DDD_Design.md Section 10.4) - `internal`, matches the
         * repository-only visibility of every other aggregate's
         * `reconstitute()`.
         */
        internal fun reconstitute(
            id: StockItemId,
            companyId: CompanyId,
            name: String,
            currency: Currency,
            stage: InventoryStage,
            quantityOnHand: BigDecimal,
            unitCost: Money,
            nrvWriteDownPerUnit: Money
        ): StockItem {
            val stockItem = StockItem(id, companyId, name, currency, stage)
            stockItem.quantityOnHand = quantityOnHand
            stockItem.unitCost = unitCost
            stockItem.nrvWriteDownPerUnit = nrvWriteDownPerUnit
            return stockItem
        }
    }
}
