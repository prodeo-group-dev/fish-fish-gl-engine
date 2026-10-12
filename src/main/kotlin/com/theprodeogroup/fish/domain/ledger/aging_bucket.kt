package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import java.time.LocalDate

/**
 * Standard aging buckets, shared by `AccountsReceivableAging` (AR,
 * `domain.sales`) and `AccountsPayableAging` (AP, `domain.purchasing`) -
 * docs/DDD_Design.md
 * Section 2.5. Measured in days since the *recognition* date (a sale or
 * a purchase), not a due date - no credit-terms/due-date concept exists
 * anywhere in this codebase yet, so "overdue" here really means "days
 * since recognized," a simplification worth revisiting once payment
 * terms are modeled.
 *
 * Lives in `domain.ledger`, not `domain.sales`/`domain.purchasing` -
 * this is a generic data shape, not an algorithm; relocated here
 * 2026-08-12 when the AP side needed the same bucket labels, rather
 * than letting `domain.purchasing` reach into `domain.sales` for them.
 */
enum class AgingBucketLabel {
    /** 0-30 days. */
    CURRENT,

    /** 31-60 days. */
    DAYS_31_TO_60,

    /** 61-90 days. */
    DAYS_61_TO_90,

    /** 90+ days. */
    OVER_90;

    companion object {
        /** The bucket an item [days] old falls in: up to 30 is CURRENT, up to 60, up to 90, then OVER_90. */
        fun forDaysOld(days: Long): AgingBucketLabel = when {
            days <= 30 -> CURRENT
            days <= 60 -> DAYS_31_TO_60
            days <= 90 -> DAYS_61_TO_90
            else -> OVER_90
        }
    }
}

data class AgingBucketAmount(
    val label: AgingBucketLabel,
    val amount: Money
)

/**
 * One sale (or purchase) that is still unpaid after the first-in-first-out allocation of receipts (or payments), with
 * the bucket its age puts it in (a part-paid sale is one item for the unpaid remainder). The same items the bucket
 * totals are made of, exposed so a summary can count them (receivables-payables-summary); the bucket totals themselves
 * are unchanged.
 */
data class UnpaidItem(val date: LocalDate, val amount: Money, val bucket: AgingBucketLabel)
