package com.theprodeogroup.fish.domain.purchasing

/**
 * When control of GOODS lines transfers from supplier to buyer -
 * `docs/IFRS_GL_Posting_Matrix.md` Part A's Case 1 vs. Case 3, the "real
 * gap" flagged in that document's own "status against what's built"
 * section. Named around the *accounting effect* (control-transfer
 * timing), not raw incoterm jargon (FOB/CIF/EXW) - the matrix itself
 * only gives an explicit GL treatment for the two ends of this
 * spectrum, and different incoterms can map to either depending on the
 * specific contract, so asserting a fixed incoterm-to-treatment mapping
 * here would be guessing rather than reading what's actually specified.
 *
 * [CONTROL_TRANSFERS_AT_SHIPMENT] is [PurchaseOrder.send]'s original,
 * only behaviour (Matrix Case 3 / A.1.3) - GOODS lines debit their own
 * Inventory account directly and [com.theprodeogroup.fish.domain.inventory.StockItem.recordReceipt]
 * fires immediately at send, since control (and therefore the right to
 * recognise inventory) has already passed. This remains [PurchaseOrder.create]'s
 * default, so every `PurchaseOrder` built before this existed keeps its
 * exact original behaviour unchanged.
 *
 * [CONTROL_TRANSFERS_AT_RECEIPT] is the new path (Matrix Case 1 / A.1.1,
 * the "standard credit purchase"): GOODS lines are not yet controlled at
 * send, so [PurchaseOrder.send] debits a caller-supplied Goods-in-Transit
 * account instead of Inventory, and [com.theprodeogroup.fish.domain.inventory.StockItem.recordReceipt]
 * is deferred until [PurchaseOrder.receiveGoods] actually reclassifies
 * Goods-in-Transit into Inventory. The GRNI path
 * ([PurchaseOrder.receiveGoodsBeforeInvoice]/[PurchaseOrder.matchInvoice],
 * Matrix Case A.2.2/C.1.2 - goods arrive *before* the invoice) only
 * applies under this same delivery term; under
 * [CONTROL_TRANSFERS_AT_SHIPMENT], inventory is already fully recognised
 * at send regardless of physical receipt timing, so there is nothing
 * left for a later physical arrival to trigger financially - that
 * remains purely `IM/`'s `InTransitShipment`/`LocationState` concern
 * (physical custody, not a GL posting).
 */
enum class DeliveryTerms {
    CONTROL_TRANSFERS_AT_SHIPMENT,
    CONTROL_TRANSFERS_AT_RECEIPT
}
