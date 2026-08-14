package com.theprodeogroup.fish.domain.ledger

/**
 * A manufacturing-accounting sub-classification for Expense accounts
 * (docs/DDD_Design.md Section 2.1/2.6), confirmed 2026-08-12 as the
 * first step toward a three-stage **Manufacturing Account -> Trading
 * Account -> Profit & Loss Account** structure - not a two-stage
 * Trading/P&L split. Production wages sit inside the Manufacturing
 * Account as Direct Labor, not directly in Trading.
 *
 * [DIRECT_MATERIAL] + [DIRECT_LABOR] + [DIRECT_EXPENSE] sum to Prime
 * Cost; + [FACTORY_OVERHEAD] gives Total Manufacturing Cost (adjusted
 * for Work-in-Progress, `InventoryStage`/`StockItem`, to reach Cost of
 * Production) - together, the Manufacturing Account, whose output feeds
 * the Trading Account's Cost of Sales for a manufacturer.
 *
 * [COST_OF_GOODS_SOLD] (added 2026-08-14, closing a real gap found
 * while building `ManufacturingTradingProfitAndLossAccount`) is the
 * *other* way into the Trading Account's Cost of Sales - a pure
 * trading company's Cost of Goods Sold (goods bought and resold as-is,
 * e.g. `SalesOrder.deliverLine()`'s `cogsExpenseAccountId`), which
 * never passes through a Manufacturing Account stage at all. Without
 * this value, a trader's COGS account would either be miscounted as a
 * P&L operating expense, or - worse - every unclassified Expense
 * account would have to be *assumed* to be Cost of Sales, which breaks
 * for a services company with no COGS concept whatsoever. Tagging the
 * account explicitly avoids both.
 *
 * [ADMINISTRATIVE]/[SELLING_DISTRIBUTION] are ordinary Profit & Loss
 * Account overheads, unrelated to production or cost of sales.
 */
enum class ExpenseClassification {
    DIRECT_MATERIAL,
    DIRECT_LABOR,
    DIRECT_EXPENSE,
    FACTORY_OVERHEAD,
    COST_OF_GOODS_SOLD,
    ADMINISTRATIVE,
    SELLING_DISTRIBUTION
}
