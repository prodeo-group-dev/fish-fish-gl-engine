package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table definitions for the Tax context
 * (`V5__tax_tables.sql`, docs/DDD_Design.md Section 10.11) - matches the
 * migration's DDL exactly.
 */
object TaxRulesTable : Table("tax_rules") {
    val id = uuid("id")
    val jurisdiction = varchar("jurisdiction", 100)
    val taxType = varchar("tax_type", 30)

    /**
     * Replaces the original `rate NUMERIC(19,4)` column
     * (`V9__tax_rule_rate_structure.sql`) - holds a
     * `RateStructure` encoded via [encodeRateStructure]/
     * [decodeRateStructure], same hand-written-text convention as
     * `journal_lines.dimensions`.
     */
    val rateStructure = text("rate_structure")

    override val primaryKey = PrimaryKey(id)
}

object TaxComputationsTable : Table("tax_computations") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val periodId = uuid("period_id")
    val taxRuleId = uuid("tax_rule_id")
    val taxableProfitAmount = decimal("taxable_profit_amount", 19, 4)
    val taxDueAmount = decimal("tax_due_amount", 19, 4)
    val currency = varchar("currency", 3)
    val computedAt = timestamp("computed_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * `V25__vat_returns.sql` - only the top-line net figure is persisted
 * (see that migration's own comment for why `categoryBreakdown` isn't:
 * always re-derivable from already-posted, tagged `journal_lines`, so
 * storing a second copy would just be a cache that could drift).
 */
object VatReturnsTable : Table("vat_returns") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val filingPeriodStartDate = date("filing_period_start_date")
    val filingPeriodEndDate = date("filing_period_end_date")
    val vatControlAccountId = uuid("vat_control_account_id")
    val outputVatAmount = decimal("output_vat_amount", 19, 4)
    val inputVatAmount = decimal("input_vat_amount", 19, 4)
    val netVatDueAmount = decimal("net_vat_due_amount", 19, 4)
    val direction = varchar("direction", 20)
    val currency = varchar("currency", 3)
    val computedAt = timestamp("computed_at")

    override val primaryKey = PrimaryKey(id)
}
