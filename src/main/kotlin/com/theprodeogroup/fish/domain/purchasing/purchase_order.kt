package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.Instant
import java.time.LocalDate

/**
 * An order to a supplier (docs/DDD_Design.md Section 2.5) - Increment 1
 * of Purchase Order Processing. Cancellation is deliberately not
 * modeled yet. Goods-receipt/Inventory linkage was deferred originally
 * but is now built (2026-08-12, [send]'s `stockItems` parameter) - this
 * still covers only what's needed to recognize Accounts Payable and
 * receive stock when the order is sent, not a separate goods-receipt
 * event/lifecycle stage.
 */
class PurchaseOrder private constructor(
    val id: PurchaseOrderId,
    val companyId: CompanyId,
    val creditorId: CreditorId,
    val date: LocalDate,
    val lines: List<PurchaseOrderLine>
) {
    var status: PurchaseOrderStatus = PurchaseOrderStatus.DRAFT
        private set

    val totalAmount: Money
        get() = lines.map { it.amount }.reduce { a, b -> a + b }

    /**
     * Section 2.5: "when we order we owe" - AP is recognized in full
     * here, not progressively. Debits each line's Account, credits
     * [apControlAccountId] for the total, and charges [creditor]'s
     * subsidiary balance in the same call - control account and
     * subsidiary ledger move together, by construction, to stay in tally
     * (the actual tally *check* is a separate reporting concern, Section
     * 3.1's Trial Balance note).
     *
     * The AP control line carries `DimensionType.VENDOR` tagged with
     * [creditorId] - previously `JournalLine.dimensions` was never
     * populated anywhere despite existing for exactly this purpose,
     * meaning the Ledger had no record of which Creditor a posting
     * belonged to. This is what lets aging/reporting be derived from
     * already-posted `JournalEntry` data instead of needing `Creditor`
     * to redundantly track its own transaction history.
     *
     * Returns null (not `ValidationResult`) if this order isn't `Draft`
     * or [creditor] doesn't match [creditorId] - matches
     * `JournalEntry.reverse()`'s precedent for a method that produces a
     * new object on success rather than just mutating `this`.
     *
     * Confirmed 2026-08-12 - the previously-deferred PO/SO-Inventory
     * linkage (Section 2.6): for every `GOODS` line, the matching
     * `StockItem` in [stockItems] (by `stockItemId`) has
     * `StockItem.recordReceipt` called with the line's quantity and a
     * unit cost derived as `amount / quantity` (no separate unit-price
     * field exists). **No new `JournalLine`s are needed for this** -
     * unlike the Sales side, receiving goods doesn't create a new
     * financial fact; the existing debit to `line.accountId` already
     * *is* "Dr Inventory" as long as the caller points a `GOODS` line's
     * `accountId` at the Inventory Asset account, which is the caller's
     * responsibility, not something enforced here. Returns `null` if a
     * `GOODS` line's `StockItem` is missing from [stockItems] or there's
     * a currency mismatch computing unit cost.
     */
    fun send(
        creditor: Creditor,
        apControlAccountId: AccountId,
        periodId: PeriodId,
        stockItems: List<StockItem> = emptyList(),
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (!status.canTransitionTo(PurchaseOrderStatus.SENT)) return null
        if (creditor.id != creditorId) return null

        for (line in lines) {
            if (line.itemType != LineItemType.GOODS) continue
            val stockItem = stockItems.find { it.id == line.stockItemId } ?: return null
            val unitCost = line.amount / line.quantity!!
            if (!stockItem.recordReceipt(line.quantity, unitCost).isValid) return null
        }

        status = PurchaseOrderStatus.SENT
        val total = totalAmount
        val journalLines = lines.map { JournalLine(it.accountId, it.amount, TransactionSide.DEBIT) } +
            JournalLine(
                apControlAccountId, total, TransactionSide.CREDIT,
                mapOf(DimensionType.VENDOR to creditorId.value.toString())
            )
        val entry = JournalEntry.create(
            periodId, date, journalLines, JournalSource.MANUAL, "Purchase Order $id", journalEntryId
        )
        creditor.recordCharge(total)
        return entry
    }

    companion object {
        fun create(
            companyId: CompanyId,
            creditorId: CreditorId,
            date: LocalDate,
            lines: List<PurchaseOrderLine>,
            id: PurchaseOrderId = PurchaseOrderId.generate()
        ): PurchaseOrder {
            require(lines.isNotEmpty()) { "A PurchaseOrder must have at least one line" }
            return PurchaseOrder(id, companyId, creditorId, date, lines)
        }
    }
}
