package com.theprodeogroup.fish.domain.ledger

/**
 * A manufacturing-accounting sub-classification for Expense accounts
 * (docs/DDD_Design.md Section 2.1/2.6), confirmed 2026-08-12 as the
 * first step toward a three-stage **Manufacturing Account -> Trading
 * Account -> Profit & Loss Account** structure - not a two-stage
 * Trading/P&L split. Production wages sit inside the Manufacturing
 * Account as Direct Labor, not directly in Trading; a manufacturing
 * company's Cost of Goods Sold is fed by the Manufacturing Account's
 * output (Cost of Production), not computed directly from Expense
 * accounts the way a pure trading company's is.
 *
 * [DIRECT_MATERIAL] + [DIRECT_LABOR] + [DIRECT_EXPENSE] sum to Prime
 * Cost; + [FACTORY_OVERHEAD] gives Total Manufacturing Cost (adjusted
 * for Work-in-Progress - not yet modeled - to reach Cost of
 * Production) - together, the Manufacturing Account.
 * [ADMINISTRATIVE]/[SELLING_DISTRIBUTION] are ordinary Profit & Loss
 * Account overheads, unrelated to production.
 *
 * This is only the classification itself - `ProfitAndLoss` doesn't yet
 * compute a cascading Manufacturing -> Trading -> P&L result from it
 * (deliberately deferred, confirmed as the smallest first step rather
 * than building all three pieces - Inventory's WIP stage, this
 * classification, and the report - at once).
 */
enum class ExpenseClassification {
    DIRECT_MATERIAL,
    DIRECT_LABOR,
    DIRECT_EXPENSE,
    FACTORY_OVERHEAD,
    ADMINISTRATIVE,
    SELLING_DISTRIBUTION
}
