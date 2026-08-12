package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.ledger.Money

/**
 * Standard AR aging buckets (docs/DDD_Design.md Section 2.5) - measured
 * in days since the *sale* date (recognition date), not a due date. No
 * credit-terms/due-date concept exists anywhere in this codebase yet, so
 * "overdue" here really means "days since recognized" - a simplification
 * worth revisiting once payment terms are modeled.
 */
enum class AgingBucketLabel {
    /** 0-30 days. */
    CURRENT,

    /** 31-60 days. */
    DAYS_31_TO_60,

    /** 61-90 days. */
    DAYS_61_TO_90,

    /** 90+ days. */
    OVER_90
}

data class AgingBucketAmount(
    val label: AgingBucketLabel,
    val amount: Money
)
