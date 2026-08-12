package com.theprodeogroup.fish.domain.ledger

/**
 * IAS 7's three cash-flow activity categories (docs/DDD_Design.md Section
 * 2.1). Stored as a `DimensionType.CASH_FLOW_ACTIVITY` tag (`.name`) on
 * the cash/bank `JournalLine` itself, not derived from the counter-account
 * - see `StatementOfCashFlows`'s KDoc for why the derive-from-counter-
 * account approach was tried and rejected first.
 */
enum class CashFlowActivity {
    OPERATING,
    INVESTING,
    FINANCING
}
