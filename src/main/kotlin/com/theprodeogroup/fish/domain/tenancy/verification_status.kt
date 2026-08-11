package com.theprodeogroup.fish.domain.tenancy

/**
 * Outcome of an external identity/business verification check.
 *
 * FiSH does not perform AML/KYB/KYC screening itself (spec Section 5.2, out
 * of scope) - this only records the outcome of an external check and gates
 * on it. Shared by two distinct checks on `Tenant` (docs/DDD_Design.md
 * Section 9.2/9.4):
 * - `kybStatus`: the business-level KYB check.
 * - `adminKycStatus`: personal KYC on the admin User created at onboarding
 *   (step 4). Required for AML: verifying the business without verifying
 *   who controls it leaves exactly the gap AML regulation exists to close
 *   (an unverified controller behind a verified-looking business). So the
 *   founding admin's KYC is part of onboarding, not deferred like the
 *   per-member common-bond/KYC declaration in spec Section 7.11 (which only
 *   applies to members onboarded later, once the Tenant is already Active).
 *
 * Both fields use the same 180-day grace period and the same activation
 * rule (Pending doesn't block, Flagged does) - see [blocksActivation].
 */
enum class VerificationStatus {
    /**
     * Check requested/in progress, no outcome yet.
     * Does not block Tenant activation (docs/DDD_Design.md Section 9.4) -
     * only tracked against the 180-day verification grace period.
     */
    PENDING,

    /**
     * Check passed. Clears the grace-period deadline for this field.
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
