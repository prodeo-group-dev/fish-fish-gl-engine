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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * An order to a supplier (docs/DDD_Design.md Section 2.5) - Increment 1
 * of Purchase Order Processing. Cancellation is deliberately not
 * modeled yet.
 *
 * **Goods-in-Transit/GRNI (`docs/IFRS_GL_Posting_Matrix.md` Part A),
 * added 2026-08-21.** [deliveryTerms] decides which of two paths a
 * GOODS-bearing order follows - see [DeliveryTerms]'s own KDoc for the
 * full reasoning. [DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT] is
 * [send]'s original, unchanged behaviour (also [create]'s default, so
 * every pre-existing caller keeps its exact prior behaviour).
 * [DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT] adds two parallel
 * two-step paths depending on which document arrives first:
 * - Invoice-first: [send] (Dr Goods-in-Transit, Cr AP) -> [receiveGoods]
 *   (Dr Inventory, Cr Goods-in-Transit).
 * - Goods-first (GRNI): [receiveGoodsBeforeInvoice] (Dr Inventory, Cr
 *   GRNI) -> [matchInvoice] (Dr GRNI + any SERVICE lines' own accounts,
 *   Cr AP).
 *
 * Mixed GOODS+SERVICE orders are handled without restriction: whichever
 * method actually recognises AP for a given order (`send` in the
 * invoice-first path, `matchInvoice` in the GRNI path) is the one that
 * charges SERVICE lines to their own accounts - GOODS lines never touch
 * SERVICE lines' accounts and vice versa.
 */
class PurchaseOrder private constructor(
    val id: PurchaseOrderId,
    val companyId: CompanyId,
    val creditorId: CreditorId,
    val date: LocalDate,
    val lines: List<PurchaseOrderLine>,
    val deliveryTerms: DeliveryTerms
) {
    var status: PurchaseOrderStatus = PurchaseOrderStatus.DRAFT
        private set

    /** Set by [send] (invoice-first path) or [receiveGoodsBeforeInvoice] (GRNI path) - never both on the same order. */
    var goodsInTransitAccountId: AccountId? = null
        private set

    var grniAccountId: AccountId? = null
        private set

    val totalAmount: Money
        get() = lines.map { it.amount }.reduce { a, b -> a + b }

    private val goodsLines: List<PurchaseOrderLine>
        get() = lines.filter { it.itemType == LineItemType.GOODS }

    private val goodsTotal: Money?
        get() = goodsLines.map { it.amount }.reduceOrNull { a, b -> a + b }

    /**
     * Section 2.5: "when we order we owe" - AP is recognized in full
     * here, not progressively, regardless of [deliveryTerms]. What
     * varies by [deliveryTerms] is only *which account* a GOODS line
     * debits and *whether* [StockItem.recordReceipt] fires now or is
     * deferred to [receiveGoods]:
     *
     * - [DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT] (default, original
     *   behaviour): each GOODS line debits its own Inventory account
     *   ([PurchaseOrderLine.accountId]) and [StockItem.recordReceipt]
     *   fires immediately - control has already passed, so this order
     *   is expected to reach a terminal [PurchaseOrderStatus.SENT] and
     *   never call [receiveGoods].
     * - [DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT]: each GOODS line
     *   instead debits [goodsInTransitAccountId] (required, non-null,
     *   whenever this order has at least one GOODS line under this
     *   delivery term - returns `null` if missing), and
     *   [StockItem.recordReceipt] is deferred until [receiveGoods].
     *   SERVICE lines are entirely unaffected by [deliveryTerms] - they
     *   always debit their own account here, since a service has no
     *   physical-control-transfer ambiguity to defer.
     *
     * Returns `null` if this order isn't `Draft`, [creditor] doesn't
     * match [creditorId], a GOODS line's `StockItem` is missing from
     * [stockItems] under the [DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT]
     * path, there's a currency mismatch computing unit cost, or
     * [goodsInTransitAccountId] is required but not supplied.
     */
    fun send(
        creditor: Creditor,
        apControlAccountId: AccountId,
        periodId: PeriodId,
        stockItems: List<StockItem> = emptyList(),
        goodsInTransitAccountId: AccountId? = null,
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (!status.canTransitionTo(PurchaseOrderStatus.SENT)) return null
        if (creditor.id != creditorId) return null

        val deferGoodsRecognition = deliveryTerms == DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        if (deferGoodsRecognition && goodsLines.isNotEmpty() && goodsInTransitAccountId == null) return null

        if (!deferGoodsRecognition) {
            for (line in goodsLines) {
                val stockItem = stockItems.find { it.id == line.stockItemId } ?: return null
                val unitCost = line.amount / line.quantity!!
                if (!stockItem.recordReceipt(line.quantity, unitCost).isValid) return null
            }
        }

        status = PurchaseOrderStatus.SENT
        if (deferGoodsRecognition && goodsLines.isNotEmpty()) {
            this.goodsInTransitAccountId = goodsInTransitAccountId
        }

        val total = totalAmount
        val journalLines = lines.map { line ->
            val debitAccountId = if (deferGoodsRecognition && line.itemType == LineItemType.GOODS) {
                requireNotNull(goodsInTransitAccountId)
            } else {
                line.accountId
            }
            JournalLine(debitAccountId, line.amount, TransactionSide.DEBIT)
        } + JournalLine(
            apControlAccountId, total, TransactionSide.CREDIT,
            mapOf(DimensionType.VENDOR to creditorId.value.toString())
        )
        val entry = JournalEntry.create(
            periodId, date, journalLines, JournalSource.MANUAL, "Purchase Order $id", journalEntryId
        )
        creditor.recordCharge(total)
        return entry
    }

    /**
     * Invoice-first path's second step (`docs/IFRS_GL_Posting_Matrix.md`
     * A.3.1) - reclassifies Goods-in-Transit into Inventory once goods
     * are actually physically received: Dr each GOODS line's own
     * Inventory account, Cr [goodsInTransitAccountId] for the GOODS
     * total. [StockItem.recordReceipt] fires here, deferred from [send]
     * for exactly this moment.
     *
     * Returns `null` if this order isn't [PurchaseOrderStatus.SENT],
     * [deliveryTerms] isn't [DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT]
     * (nothing to reclassify under the other delivery term - `send`
     * already recognised Inventory directly), there are no GOODS lines
     * at all, a GOODS line's `StockItem` is missing from [stockItems],
     * or there's a currency mismatch computing unit cost.
     */
    fun receiveGoods(
        stockItems: List<StockItem>,
        periodId: PeriodId,
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (status != PurchaseOrderStatus.SENT) return null
        if (deliveryTerms != DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT) return null
        val goods = goodsLines
        if (goods.isEmpty()) return null
        val gitAccountId = goodsInTransitAccountId ?: return null

        for (line in goods) {
            val stockItem = stockItems.find { it.id == line.stockItemId } ?: return null
            val unitCost = line.amount / line.quantity!!
            if (!stockItem.recordReceipt(line.quantity, unitCost).isValid) return null
        }

        status = PurchaseOrderStatus.RECEIVED
        val total = requireNotNull(goodsTotal)
        val journalLines = goods.map { line -> JournalLine(line.accountId, line.amount, TransactionSide.DEBIT) } +
            JournalLine(gitAccountId, total, TransactionSide.CREDIT)
        return JournalEntry.create(
            periodId, date, journalLines, JournalSource.MANUAL, "Purchase Order $id - goods received", journalEntryId
        )
    }

    /**
     * GRNI path's first step (`docs/IFRS_GL_Posting_Matrix.md` C.1.2) -
     * goods physically arrive before the supplier invoice. Dr each
     * GOODS line's own Inventory account, Cr [grniAccountId] (an
     * Accrued Liability) for the GOODS total. [StockItem.recordReceipt]
     * fires immediately - unlike the invoice-first path, physical
     * control has already passed the moment goods arrive; only the AP
     * liability recognition is pending, not the Inventory recognition.
     * SERVICE lines are untouched here - they wait for [matchInvoice].
     *
     * Returns `null` if this order isn't `Draft`, [deliveryTerms] isn't
     * [DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT] (under
     * [DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT], `send` already
     * recognises Inventory regardless of physical receipt timing - GRNI
     * doesn't apply), there are no GOODS lines, a GOODS line's
     * `StockItem` is missing from [stockItems], or there's a currency
     * mismatch computing unit cost.
     */
    fun receiveGoodsBeforeInvoice(
        stockItems: List<StockItem>,
        grniAccountId: AccountId,
        periodId: PeriodId,
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (!status.canTransitionTo(PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE)) return null
        if (deliveryTerms != DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT) return null
        val goods = goodsLines
        if (goods.isEmpty()) return null

        for (line in goods) {
            val stockItem = stockItems.find { it.id == line.stockItemId } ?: return null
            val unitCost = line.amount / line.quantity!!
            if (!stockItem.recordReceipt(line.quantity, unitCost).isValid) return null
        }

        status = PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE
        this.grniAccountId = grniAccountId
        val total = requireNotNull(goodsTotal)
        val journalLines = goods.map { line -> JournalLine(line.accountId, line.amount, TransactionSide.DEBIT) } +
            JournalLine(grniAccountId, total, TransactionSide.CREDIT)
        return JournalEntry.create(
            periodId, date, journalLines, JournalSource.MANUAL, "Purchase Order $id - goods received, pending invoice", journalEntryId
        )
    }

    /**
     * GRNI path's second step (`docs/IFRS_GL_Posting_Matrix.md` A.2.2 -
     * "invoice arrives later") - the supplier invoice finally arrives
     * and is matched. Dr [grniAccountId] for the *accrued* GOODS total
     * (clearing the accrual at the amount it was actually raised for)
     * plus Dr each SERVICE line's own account (deferred from
     * [receiveGoodsBeforeInvoice], which only ever touches GOODS lines),
     * Cr [apControlAccountId] for the *actual* total - AP is recognised
     * in one call here, same "when we order we owe, in full" principle
     * [send] already applies to its own path.
     *
     * **Variance handling, added 2026-08-21** (the GRNI reference
     * material's own "if invoice amount differs from GRNI accrual"
     * case) - real invoices rarely match the PO/GRNI accrual exactly.
     * [invoicedGoodsAmount] is the actual invoiced figure for the GOODS
     * portion; `null` (the default) means "trust the accrual exactly,"
     * preserving this method's original behaviour byte-for-byte when
     * omitted. Any non-zero difference from the accrued [goodsTotal]
     * posts as its own line to [varianceAccountId] - a cost increase
     * (invoice higher than accrued) debits it, a decrease credits it -
     * required whenever there's an actual difference, since this
     * aggregate has no way to decide on its own whether the variance is
     * cost-attributable (Inventory) or not (a Purchase Price Variance
     * account); that judgement, and which account it maps to, is the
     * caller's to make. Returns `null` if there's a non-zero variance
     * with no [varianceAccountId] supplied, or [invoicedGoodsAmount]'s
     * currency doesn't match the accrual's.
     *
     * Returns `null` if this order isn't
     * [PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE] or [creditor]
     * doesn't match [creditorId].
     */
    fun matchInvoice(
        creditor: Creditor,
        apControlAccountId: AccountId,
        periodId: PeriodId,
        invoicedGoodsAmount: Money? = null,
        varianceAccountId: AccountId? = null,
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (status != PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE) return null
        if (creditor.id != creditorId) return null
        val grni = grniAccountId ?: return null
        val accruedGoodsTotal = requireNotNull(goodsTotal)
        val actualGoodsTotal = invoicedGoodsAmount ?: accruedGoodsTotal
        if (actualGoodsTotal.currency != accruedGoodsTotal.currency) return null
        val variance = actualGoodsTotal - accruedGoodsTotal
        if (variance.amount.signum() != 0 && varianceAccountId == null) return null

        status = PurchaseOrderStatus.RECEIVED
        val goodsClearingLine = JournalLine(grni, accruedGoodsTotal, TransactionSide.DEBIT)
        val varianceLines = if (variance.amount.signum() == 0) {
            emptyList()
        } else if (variance.amount.signum() > 0) {
            listOf(JournalLine(requireNotNull(varianceAccountId), variance, TransactionSide.DEBIT))
        } else {
            val zero = Money(BigDecimal.ZERO, variance.currency)
            listOf(JournalLine(requireNotNull(varianceAccountId), zero - variance, TransactionSide.CREDIT))
        }
        val serviceLines = lines
            .filter { it.itemType != LineItemType.GOODS }
            .map { line -> JournalLine(line.accountId, line.amount, TransactionSide.DEBIT) }
        val total = totalAmount - accruedGoodsTotal + actualGoodsTotal
        val journalLines = listOf(goodsClearingLine) + varianceLines + serviceLines + JournalLine(
            apControlAccountId, total, TransactionSide.CREDIT,
            mapOf(DimensionType.VENDOR to creditorId.value.toString())
        )
        val entry = JournalEntry.create(
            periodId, date, journalLines, JournalSource.MANUAL, "Purchase Order $id - invoice matched", journalEntryId
        )
        creditor.recordCharge(total)
        return entry
    }

    /**
     * GRNI path's reversal (the GRNI reference material's own "if goods
     * are returned before invoice" case, 2026-08-21) - goods physically
     * go back to the supplier before any invoice arrived. Dr
     * [grniAccountId] (clearing the accrual), Cr each GOODS line's own
     * Inventory account, for the accrued GOODS total.
     * [StockItem.recordIssue] reverses the quantity
     * [receiveGoodsBeforeInvoice] added.
     *
     * Transitions back to [PurchaseOrderStatus.DRAFT], not a dedicated
     * "returned" state - see [PurchaseOrderStatus]'s own KDoc for why:
     * with no goods and no invoice, this order is exactly where Case 2
     * already says an order should sit (no recognisable asset or
     * liability), and [DRAFT] already permits a replacement shipment to
     * call [receiveGoodsBeforeInvoice] again with no special casing.
     *
     * A full reversal only - matches [receiveGoods]/[receiveGoodsBeforeInvoice]/
     * [matchInvoice]'s own whole-order granularity; nothing in this
     * aggregate models a partial-quantity return.
     *
     * Returns `null` if this order isn't
     * [PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE], a GOODS
     * line's `StockItem` is missing from [stockItems], or reversing the
     * quantity fails (e.g. some of it has already been issued
     * elsewhere).
     */
    fun returnGoodsBeforeInvoice(
        stockItems: List<StockItem>,
        periodId: PeriodId,
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (status != PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE) return null
        val grni = grniAccountId ?: return null
        val goods = goodsLines

        for (line in goods) {
            val stockItem = stockItems.find { it.id == line.stockItemId } ?: return null
            if (!stockItem.recordIssue(line.quantity!!).isValid) return null
        }

        status = PurchaseOrderStatus.DRAFT
        this.grniAccountId = null
        val total = requireNotNull(goodsTotal)
        val journalLines = listOf(JournalLine(grni, total, TransactionSide.DEBIT)) +
            goods.map { line -> JournalLine(line.accountId, line.amount, TransactionSide.CREDIT) }
        return JournalEntry.create(
            periodId, date, journalLines, JournalSource.MANUAL, "Purchase Order $id - goods returned before invoice", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            creditorId: CreditorId,
            date: LocalDate,
            lines: List<PurchaseOrderLine>,
            deliveryTerms: DeliveryTerms = DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT,
            id: PurchaseOrderId = PurchaseOrderId.generate()
        ): PurchaseOrder {
            require(lines.isNotEmpty()) { "A PurchaseOrder must have at least one line" }
            return PurchaseOrder(id, companyId, creditorId, date, lines, deliveryTerms)
        }

        /**
         * Rebuilds an already-valid PurchaseOrder from persisted state
         * (docs/DDD_Design.md Section 10.4) - bypasses [create]'s
         * always-Draft starting point, same reason
         * `Account`/`Period`/`JournalEntry.reconstitute()` bypass their
         * own `create()` validation. `internal`, matches the
         * repository-only visibility precedent.
         */
        internal fun reconstitute(
            id: PurchaseOrderId,
            companyId: CompanyId,
            creditorId: CreditorId,
            date: LocalDate,
            lines: List<PurchaseOrderLine>,
            status: PurchaseOrderStatus,
            deliveryTerms: DeliveryTerms = DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT,
            goodsInTransitAccountId: AccountId? = null,
            grniAccountId: AccountId? = null
        ): PurchaseOrder {
            val purchaseOrder = PurchaseOrder(id, companyId, creditorId, date, lines, deliveryTerms)
            purchaseOrder.status = status
            purchaseOrder.goodsInTransitAccountId = goodsInTransitAccountId
            purchaseOrder.grniAccountId = grniAccountId
            return purchaseOrder
        }
    }
}
