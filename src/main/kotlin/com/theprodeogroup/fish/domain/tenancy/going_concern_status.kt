package com.theprodeogroup.fish.domain.tenancy

/**
 * Whether a Company is presumed to continue operating (docs/DDD_Design.md
 * Section 9.7) - the standard accounting going-concern assumption, not a
 * synonym for "has existing history." Every Company defaults to [ASSUMED]
 * regardless of whether it's brand new or established.
 *
 * Deliberately no transition back from [SUBSTANTIAL_DOUBT] to [ASSUMED] -
 * whether/how that should work (auto-revert vs. requiring an explicit
 * accountant/compliance re-assessment) is still an open question per
 * Section 9.7, not built ahead of that decision.
 */
enum class GoingConcernStatus {
    /** No known events or conditions raising doubt. The default. */
    ASSUMED,

    /**
     * A business borrower's ArrearsCase (Lending context, Section 3.3)
     * reaching Final Resolution moves the Company here - not earlier
     * arrears stages, which aren't yet indicative of real risk.
     */
    SUBSTANTIAL_DOUBT;

    fun canTransitionTo(newStatus: GoingConcernStatus): Boolean {
        return this == ASSUMED && newStatus == SUBSTANTIAL_DOUBT
    }
}
