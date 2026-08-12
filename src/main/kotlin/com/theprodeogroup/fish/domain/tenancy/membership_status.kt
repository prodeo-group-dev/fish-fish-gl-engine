package com.theprodeogroup.fish.domain.tenancy

/**
 * Whether a Membership currently grants access (docs/DDD_Design.md
 * Section 3.2) - not specified anywhere in the spec, a minimal default
 * matching `Account`'s own `active`/`deactivate()`/`reactivate()`
 * precedent. Unlike `GoingConcernStatus` (deliberately one-way, an
 * open policy question), reactivation here is allowed in both
 * directions - no confirmed policy blocks it, and re-granting access
 * to someone who left and came back is an ordinary case.
 */
enum class MembershipStatus {
    ACTIVE,
    REVOKED;

    fun canTransitionTo(newStatus: MembershipStatus): Boolean = this != newStatus
}
