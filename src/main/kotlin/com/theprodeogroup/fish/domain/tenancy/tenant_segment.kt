package com.theprodeogroup.fish.domain.tenancy

/**
 * Which kind of tenant this is, per docs/DDD_Design.md Section 9.2 step 1.
 *
 * Drives onboarding friction/defaults (internal ventures are white-glove
 * provisioned by FiSH/Purse ops; external tenants go through the self-serve,
 * KYB-gated path) but does not change the underlying domain model - both
 * segments run through the same OnboardTenantUseCase and TenantStatus
 * lifecycle (Section 9.4's KYB grace period applies uniformly to both).
 */
enum class TenantSegment {
    /**
     * A FiSH-family venture (Purse, Scrip, Osusu, BuzzMe once scoped).
     * Each onboards as its own Tenant - none nested as a Company under
     * another venture's Tenant (docs/DDD_Design.md Section 8).
     */
    INTERNAL_VENTURE,

    /**
     * An external B2B SaaS customer (the Mano River SMB wedge, or any
     * general-purpose ledger customer).
     */
    EXTERNAL_B2B
}
