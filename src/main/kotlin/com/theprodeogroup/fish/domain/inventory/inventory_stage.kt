package com.theprodeogroup.fish.domain.inventory

/**
 * Where a `StockItem` sits in a manufacturing cost flow (docs/DDD_Design.md
 * Section 2.6/3.1), confirmed 2026-08-14 as the WIP-aware follow-up to
 * `ExpenseClassification` (Section 3.1) - the "harder, more novel piece"
 * flagged when that classification was scoped down to just the account
 * tag.
 *
 * Raw Materials are consumed into Work in Progress ([StockItem.consumeInto]),
 * which accumulates further cost directly ([StockItem.addProductionCost] -
 * labour, factory overhead - IAS 2's "cost accumulated to date"), and
 * completes into Finished Goods ([StockItem.completeInto]). A pure-trading
 * company (buys and resells, no manufacturing) never needs anything but
 * [FINISHED_GOODS] - the default `StockItem.create()` already assumes,
 * matching how every `StockItem` built before this existed with no stage
 * concept at all.
 */
enum class InventoryStage {
    RAW_MATERIAL,
    WORK_IN_PROGRESS,
    FINISHED_GOODS
}
