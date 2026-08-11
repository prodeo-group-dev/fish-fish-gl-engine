package com.theprodeogroup.fish.domain.ledger

/**
 * Current vs. non-current classification for Asset/Liability accounts, per
 * IAS 1 (docs/DDD_Design.md Section 3.1, added 2026-08-11).
 *
 * A liability is current unless the Company has the unconditional right to
 * defer settlement for at least 12 months. Covenant-breach-triggered
 * reclassification (a non-current liability becoming repayable on demand)
 * is a real IAS 1 rule but deliberately not modeled here yet - narrower,
 * more relevant to term debt/Scrip's treasury book than everyday trade
 * payables, and not needed for this base classification to exist.
 *
 * Only meaningful for `AccountType.ASSET`/`LIABILITY` - see
 * `AccountType.requiresClassification()`.
 */
enum class AccountClassification {
    CURRENT,
    NON_CURRENT
}
