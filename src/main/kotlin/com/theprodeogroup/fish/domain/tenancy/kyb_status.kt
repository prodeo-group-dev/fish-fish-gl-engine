package com.theprodeogroup.fish.domain.tenancy

/**
 * Outcome of a Tenant/Company-level KYB (Know Your Business) check.
 *
 * FiSH does not perform AML/KYB screening itself (spec Section 5.2, out of
 * scope) - this only records the outcome of an external check and gates on
 * it. Distinct from the per-Client common-bond/KYC declaration (spec
 * Section 7.11), which happens later, per member, once the Tenant is
 * already Active.
 */
enum class KybStatus {
    /**
     * Check requested/in progress, no outcome yet.
     * Does not block Tenant activation (docs/DDD_Design.md Section 9.4) -
     * only tracked against the 180-day verification grace period.
     */
    PENDING,

    /**
     * Check passed. Clears the grace-period deadline.
     */
    VERIFIED,

    /**
     * Check raised a concern. Unlike PENDING, this blocks activation
     * until resolved (docs/DDD_Design.md Section 9.2 step 7).
     */
    FLAGGED;

    /**
     * Returns true if this outcome blocks Tenant activation.
     */
    fun blocksActivation(): Boolean = this == FLAGGED
}
