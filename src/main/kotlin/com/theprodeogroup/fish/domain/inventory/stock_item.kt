package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.fish.domain.common.ValidationResult
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
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
 */
class StockItem private constructor(
    val id: StockItemId,
    val companyId: CompanyId,
    val name: String,
    val currency: Currency
) {
    var quantityOnHand: BigDecimal = BigDecimal.ZERO
        private set

    var unitCost: Money = Money(BigDecimal.ZERO, currency)
        private set

    val totalValue: Money
        get() = unitCost * quantityOnHand

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

    companion object {
        fun create(
            companyId: CompanyId,
            name: String,
            currency: Currency,
            id: StockItemId = StockItemId.generate()
        ): StockItem = StockItem(id, companyId, name, currency)
    }
}
