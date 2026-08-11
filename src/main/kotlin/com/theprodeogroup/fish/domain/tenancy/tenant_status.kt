package com.theprodeogroup.fish.domain.tenancy

/**
 * Status of a Tenant in its lifecycle.
 *
 * Lifecycle:
 * ```
 * DRAFT ──→ ACTIVE ⇄ SUSPENDED
 *              ↓         ↓
 *              └──→ CLOSED ←┘
 * ```
 *
 * CLOSED is terminal - cannot be reopened.
 *
 * Deliberately has no PENDING_VERIFICATION state: KYB verification does not
 * gate activation (see docs/DDD_Design.md Section 9.2 step 7 / 9.4). KYB
 * progress is tracked separately on the Tenant (kybStatus, kybVerificationDeadline),
 * orthogonal to this state machine. A Tenant can go straight from DRAFT to
 * ACTIVE with KYB still Pending.
 */
enum class TenantStatus {
    /**
     * Draft tenant - being set up
     * - Not yet operational
     * - Company/admin Membership/KYB metadata still being assembled
     */
    DRAFT,

    /**
     * Active tenant - fully operational
     * - Companies can be added, Users invited, postings made
     * - KYB may still be Pending (see 180-day grace period, Section 9.4)
     */
    ACTIVE,

    /**
     * Suspended tenant - temporarily deactivated
     * - Reversible (non-payment, a KYB flag raised post-activation,
     *   or the KYB grace period lapsing unverified)
     * - Can be reactivated back to ACTIVE
     */
    SUSPENDED,

    /**
     * Closed tenant - permanently offboarded
     * - Terminal state
     * - Data retained per spec Section 7.15 retention policy, never deleted
     */
    CLOSED;

    /**
     * Checks if transition to a new status is valid.
     *
     * Valid transitions:
     * - DRAFT -> ACTIVE
     * - ACTIVE -> SUSPENDED, CLOSED
     * - SUSPENDED -> ACTIVE, CLOSED
     * - CLOSED -> (none - terminal)
     *
     * @param newStatus Target status
     * @return True if transition is valid
     */
    fun canTransitionTo(newStatus: TenantStatus): Boolean {
        return when (this) {
            DRAFT -> newStatus == ACTIVE
            ACTIVE -> newStatus in setOf(SUSPENDED, CLOSED)
            SUSPENDED -> newStatus in setOf(ACTIVE, CLOSED)
            CLOSED -> false // Terminal state
        }
    }

    /**
     * Returns true if the tenant is operational (postings/invites allowed).
     */
    fun isOperational(): Boolean = this == ACTIVE

    /**
     * Returns true if this is a final state (no further transitions possible).
     */
    fun isFinal(): Boolean = this == CLOSED
}
