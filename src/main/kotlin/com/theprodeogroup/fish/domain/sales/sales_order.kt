package com.theprodeogroup.fish.domain.sales

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
 * An order from a customer (docs/DDD_Design.md Section 2.5) - Increment
 * 2 of the ecosystem, mirroring Purchase Order Processing's structure
 * (control account + subsidiary ledger) but with asymmetric timing:
 * placing the order recognizes nothing - income is recognized
 * progressively as delivery happens (IFRS 15), not "when they order."
 */
class SalesOrder private constructor(
    val id: SalesOrderId,
    val companyId: CompanyId,
    val customerId: CustomerId,
    val date: LocalDate,
    val lines: List<SalesOrderLine>
) {
    private val deliveredLineIndices = mutableSetOf<Int>()

    val totalAmount: Money
        get() = lines.map { it.amount }.reduce { a, b -> a + b }

    /** Computed from delivery progress, not stored/transitioned - see `SalesOrderStatus`'s KDoc. */
    val status: SalesOrderStatus
        get() = when {
            deliveredLineIndices.isEmpty() -> SalesOrderStatus.DRAFT
            deliveredLineIndices.size == lines.size -> SalesOrderStatus.FULFILLED
            else -> SalesOrderStatus.PARTIALLY_DELIVERED
        }

    /**
     * Section 2.5: "income is recognised as we deliver" - recognizes
     * *one line's* worth of revenue, not an arbitrary partial amount.
     * Delivery is modeled per-line rather than as a proportional split
     * across lines, to support genuinely progressive/staged recognition
     * (multiple deliveries over time) without needing Money
     * multiplication/division or a proportional-allocation scheme that
     * hasn't been requested - callers wanting to deliver a whole
     * multi-line order at once call this once per line.
     *
     * Debits [arControlAccountId] for the line's amount, credits the
     * line's own revenue `Account`, and calls [customer]`.recordSale()`
     * in the same call - control account and subsidiary ledger move
     * together by construction, same pattern as `PurchaseOrder.send()`.
     *
     * Returns null (not `ValidationResult`) if [lineIndex] is invalid,
     * already delivered, or [customer] doesn't match [customerId] -
     * matches `JournalEntry.reverse()`/`PurchaseOrder.send()`'s
     * precedent for a method producing a new object on success.
     *
     * The AR control line carries `DimensionType.CUSTOMER` tagged with
     * [customerId] - previously `JournalLine.dimensions` was never
     * populated anywhere despite existing for exactly this purpose,
     * meaning the Ledger had no record of which Customer a posting
     * belonged to. This is what lets aging/reporting be derived from
     * already-posted `JournalEntry` data instead of needing `Customer`
     * to redundantly track its own transaction history.
     *
     * Confirmed 2026-08-12 - the previously-deferred PO/SO-Inventory
     * linkage (Section 2.6): for a `GOODS` line, [stockItem] (matching
     * [SalesOrderLine.stockItemId]) has [StockItem.recordIssue] called
     * for the line's quantity, and the same `JournalEntry` gains two
     * more lines - debit [cogsExpenseAccountId], credit
     * [inventoryAssetAccountId] - for the cost of what was sold
     * (`stockItem.unitCost * quantity`), a single compound entry
     * recognizing both the revenue and its cost from one delivery event.
     * A `SERVICE` line ignores all three Inventory parameters entirely.
     * Returns `null` if a `GOODS` line's [stockItem]/[cogsExpenseAccountId]/
     * [inventoryAssetAccountId] are missing, [stockItem] doesn't match
     * the line's `stockItemId`, or there isn't enough stock to issue.
     */
    fun deliverLine(
        lineIndex: Int,
        customer: Customer,
        arControlAccountId: AccountId,
        periodId: PeriodId,
        stockItem: StockItem? = null,
        cogsExpenseAccountId: AccountId? = null,
        inventoryAssetAccountId: AccountId? = null,
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (lineIndex !in lines.indices) return null
        if (lineIndex in deliveredLineIndices) return null
        if (customer.id != customerId) return null

        val line = lines[lineIndex]

        val cogsLines = if (line.itemType == LineItemType.GOODS) {
            if (stockItem == null || stockItem.id != line.stockItemId) return null
            if (cogsExpenseAccountId == null || inventoryAssetAccountId == null) return null
            if (!stockItem.recordIssue(line.quantity!!).isValid) return null

            val cogsAmount = stockItem.unitCost * line.quantity
            listOf(
                JournalLine(cogsExpenseAccountId, cogsAmount, TransactionSide.DEBIT),
                JournalLine(inventoryAssetAccountId, cogsAmount, TransactionSide.CREDIT)
            )
        } else {
            emptyList()
        }

        deliveredLineIndices.add(lineIndex)

        val journalLines = listOf(
            JournalLine(
                arControlAccountId, line.amount, TransactionSide.DEBIT,
                mapOf(DimensionType.CUSTOMER to customerId.value.toString())
            ),
            JournalLine(line.accountId, line.amount, TransactionSide.CREDIT)
        ) + cogsLines
        val entry = JournalEntry.create(
            periodId, date, journalLines, JournalSource.MANUAL,
            "Sales Order $id - ${line.description}", journalEntryId
        )
        customer.recordSale(line.amount)
        return entry
    }

    /**
     * A snapshot of which line indices have been delivered - needed only
     * for persistence (docs/DDD_Design.md Section 10.4): [status] is
     * computed from [deliveredLineIndices], but that set itself has no
     * public accessor (deliberately - callers should read [status], not
     * poke at line-level delivery bookkeeping). `internal`, matches the
     * repository-only visibility of [reconstitute].
     */
    internal fun deliveredLineIndicesSnapshot(): Set<Int> = deliveredLineIndices.toSet()

    companion object {
        fun create(
            companyId: CompanyId,
            customerId: CustomerId,
            date: LocalDate,
            lines: List<SalesOrderLine>,
            id: SalesOrderId = SalesOrderId.generate()
        ): SalesOrder {
            require(lines.isNotEmpty()) { "A SalesOrder must have at least one line" }
            return SalesOrder(id, companyId, customerId, date, lines)
        }

        /**
         * Rebuilds an already-valid SalesOrder from persisted state
         * (docs/DDD_Design.md Section 10.4) - [deliveredLineIndices]
         * repopulates the private mutable set directly, since [status]
         * is *computed* from it (see the class KDoc), not a field of its
         * own - there's nothing else to set. `internal`, matches the
         * repository-only visibility precedent.
         */
        internal fun reconstitute(
            id: SalesOrderId,
            companyId: CompanyId,
            customerId: CustomerId,
            date: LocalDate,
            lines: List<SalesOrderLine>,
            deliveredLineIndices: Set<Int>
        ): SalesOrder {
            val salesOrder = SalesOrder(id, companyId, customerId, date, lines)
            salesOrder.deliveredLineIndices.addAll(deliveredLineIndices)
            return salesOrder
        }
    }
}
