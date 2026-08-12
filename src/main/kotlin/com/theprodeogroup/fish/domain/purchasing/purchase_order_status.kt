package com.theprodeogroup.fish.domain.purchasing

/**
 * Minimal status for this increment of Purchase Order Processing
 * (docs/DDD_Design.md Section 2.5) - just enough to represent "has AP
 * been recognized yet." Goods-receipt/fulfillment tracking (Inventory
 * Management) and cancellation are deliberately not modeled here yet -
 * this is Increment 1's scope, not the full module.
 */
enum class PurchaseOrderStatus {
    DRAFT,
    SENT;

    fun canTransitionTo(newStatus: PurchaseOrderStatus): Boolean {
        return this == DRAFT && newStatus == SENT
    }
}
