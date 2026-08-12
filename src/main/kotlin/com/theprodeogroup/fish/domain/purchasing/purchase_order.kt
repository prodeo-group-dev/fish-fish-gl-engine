package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
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
 * of Purchase Order Processing. Goods-receipt/fulfillment tracking
 * (Inventory Management) and cancellation are deliberately not modeled
 * yet; this covers only what's needed to recognize Accounts Payable when
 * the order is sent.
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
     */
    fun send(
        creditor: Creditor,
        apControlAccountId: AccountId,
        periodId: PeriodId,
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (!status.canTransitionTo(PurchaseOrderStatus.SENT)) return null
        if (creditor.id != creditorId) return null

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
