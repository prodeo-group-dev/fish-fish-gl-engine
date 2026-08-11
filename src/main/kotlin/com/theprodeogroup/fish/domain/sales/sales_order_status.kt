package com.theprodeogroup.fish.domain.sales

/**
 * A SalesOrder's status is *computed* from how many lines have been
 * delivered (see `SalesOrder.status`), not stored/transitioned like
 * `PurchaseOrderStatus` - there's no single "send" moment that changes
 * everything at once; income is recognized progressively as delivery
 * happens (docs/DDD_Design.md Section 2.5, IFRS 15).
 */
enum class SalesOrderStatus {
    /** No lines delivered yet - no income recognized. */
    DRAFT,

    /** Some but not all lines delivered. */
    PARTIALLY_DELIVERED,

    /** Every line delivered - full order income recognized. */
    FULFILLED
}
