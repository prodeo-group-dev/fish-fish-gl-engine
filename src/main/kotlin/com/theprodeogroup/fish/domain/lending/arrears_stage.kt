package com.theprodeogroup.fish.domain.lending

/**
 * The days-overdue escalation ladder for an ArrearsCase, sourced from
 * `docs/UK/Purse Credit Union - Ethical & Theological Framework.docx`'s
 * "Arrears Management Process" (not invented): Early Contact (1-7 days
 * overdue), Pastoral Engagement (8-30 days), Formal Review (31-60 days),
 * Final Resolution (60+ days). Strictly linear - a case can only
 * escalate to the next stage, never skip ahead or step back, matching
 * `PostingStatus`/`PeriodStatus`'s existing `canTransitionTo()` convention.
 *
 * Day-count thresholds themselves aren't enforced here - deciding *when*
 * a case should escalate (a wall-clock concern) is an application-layer
 * job, same boundary `JournalEntry` already draws against `Period`
 * (Section 3.1's design note) - this only encodes which transitions are
 * structurally valid once someone decides to escalate.
 */
enum class ArrearsStage {
    /** 1-7 days overdue - a friendly reminder. */
    EARLY_CONTACT,

    /**
     * 8-30 days overdue - a Credit Union officer makes contact and can
     * offer a payment plan restructure, a temporary payment holiday (max
     * 3 months), interest-only payments, or a financial counseling
     * referral.
     */
    PASTORAL_ENGAGEMENT,

    /**
     * 31-60 days overdue - a full financial review, offering loan
     * consolidation/refinancing, an extended term, partial forgiveness
     * for hardship cases, or voluntary asset liquidation.
     */
    FORMAL_REVIEW,

    /**
     * 60+ days overdue - board review against a "can't pay" vs. "won't
     * pay" decision matrix. Reaching this stage for a business borrower
     * is what moves the borrowing Company's `goingConcernStatus` to
     * `SUBSTANTIAL_DOUBT` (Section 9.7) - not the earlier stages.
     */
    FINAL_RESOLUTION;

    fun canTransitionTo(newStage: ArrearsStage): Boolean = when (this) {
        EARLY_CONTACT -> newStage == PASTORAL_ENGAGEMENT
        PASTORAL_ENGAGEMENT -> newStage == FORMAL_REVIEW
        FORMAL_REVIEW -> newStage == FINAL_RESOLUTION
        FINAL_RESOLUTION -> false
    }
}
