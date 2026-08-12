package com.theprodeogroup.fish.domain.lending

/**
 * How an ArrearsCase was resolved - deliberately generic, not one value
 * per exact menu item from the source policy document (interest-only
 * payments, financial counseling referrals, and voluntary asset
 * liquidation are process actions/referrals, not case-closing outcomes,
 * so they aren't modeled here). Confirmed 2026-08-12: a case can resolve
 * at *any* stage, not only at `ArrearsStage.FINAL_RESOLUTION` - a member
 * back on a restructured payment plan isn't still "in arrears."
 *
 * `CAPITALIZED_TO_EQUITY` (business-borrower-only capitalization into an
 * `EquityStake`) is deliberately not included yet - confirmed out of
 * scope for this increment, since neither `EquityStake` nor the source
 * material for that mechanism exist in enough detail to build correctly.
 */
enum class ArrearsOutcome {
    /** Payment-plan restructure, loan consolidation/refinancing, or an extended term. */
    RESTRUCTURED,

    /** A temporary payment holiday (max 3 months per the source policy). */
    PAYMENT_HOLIDAY_GRANTED,

    /** Partial loan forgiveness / negotiated settlement for a genuine hardship case. */
    PARTIAL_WRITE_OFF,

    /** Formal demand, credit reporting, court action, or membership suspension. */
    FORMAL_COLLECTION
}
