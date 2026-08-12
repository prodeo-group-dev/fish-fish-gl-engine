package com.theprodeogroup.fish.domain.common

/**
 * Whether an order line is a physical good (affects Inventory) or a
 * service (doesn't) - shared by `PurchaseOrderLine`/`SalesOrderLine`
 * (docs/DDD_Design.md Section 2.5/2.6), confirmed 2026-08-12 as the
 * concrete shape of the previously-deferred PO/SO-Inventory linkage.
 */
enum class LineItemType {
    GOODS,
    SERVICE
}
