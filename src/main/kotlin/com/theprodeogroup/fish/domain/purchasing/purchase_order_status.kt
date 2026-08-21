package com.theprodeogroup.fish.domain.purchasing

/**
 * Status for Purchase Order Processing (docs/DDD_Design.md Section 2.5),
 * extended 2026-08-21 (`docs/IFRS_GL_Posting_Matrix.md`'s Goods-in-Transit/GRNI
 * gap) beyond Increment 1's original DRAFT/SENT pair. Cancellation is
 * still deliberately not modeled.
 *
 * The structural transition matrix here only says which state changes
 * are *possible* - [DeliveryTerms]-based eligibility (e.g. [SENT] is
 * only non-terminal under [DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT])
 * is a business rule layered on top by [PurchaseOrder]'s own methods,
 * not encoded here - matching the existing split this codebase already
 * uses elsewhere between "can this state exist" (here) and "does this
 * specific business rule allow it right now" (the aggregate method).
 *
 * Two parallel paths reach [RECEIVED], depending on which document
 * arrives first (`docs/IFRS_GL_Posting_Matrix.md` Part A.1.1 vs A.2.2):
 * - Invoice-first: [DRAFT] -> [SENT] (`send`, Dr Goods-in-Transit/Cr AP)
 *   -> [RECEIVED] (`receiveGoods`, Dr Inventory/Cr Goods-in-Transit).
 * - Goods-first (GRNI): [DRAFT] -> [GOODS_RECEIVED_PENDING_INVOICE]
 *   (`receiveGoodsBeforeInvoice`, Dr Inventory/Cr GRNI) -> [RECEIVED]
 *   (`matchInvoice`, Dr GRNI/Cr AP).
 *
 * Under [DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT], [SENT] is itself
 * terminal (Inventory and AP are both recognised together, matching
 * `PurchaseOrder.send`'s original, unchanged behaviour) - no order
 * following this delivery term is expected to ever reach [RECEIVED].
 *
 * [GOODS_RECEIVED_PENDING_INVOICE] -> [DRAFT] (`PurchaseOrder.returnGoodsBeforeInvoice`,
 * added same day) is the one backward transition - goods returned to the
 * supplier before any invoice arrived leaves this order exactly where
 * Case 2 already says it should be: no asset, no liability, nothing
 * recognisable under IFRS. Reusing [DRAFT] rather than inventing a
 * dedicated `RETURNED` state means a replacement shipment can call
 * [PurchaseOrder.receiveGoodsBeforeInvoice] again without any special
 * casing - [DRAFT] already permits it.
 */
enum class PurchaseOrderStatus {
    DRAFT,
    SENT,
    GOODS_RECEIVED_PENDING_INVOICE,
    RECEIVED;

    fun canTransitionTo(newStatus: PurchaseOrderStatus): Boolean = when (this) {
        DRAFT -> newStatus == SENT || newStatus == GOODS_RECEIVED_PENDING_INVOICE
        SENT -> newStatus == RECEIVED
        GOODS_RECEIVED_PENDING_INVOICE -> newStatus == RECEIVED || newStatus == DRAFT
        RECEIVED -> false
    }
}
