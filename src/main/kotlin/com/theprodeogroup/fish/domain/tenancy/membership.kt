package com.theprodeogroup.fish.domain.tenancy

import com.theprodeogroup.common.ValidationResult

/**
 * The join of User x Tenant x Role (docs/DDD_Design.md Section 3.2,
 * spec Section 10) - a User's access within one Tenant. Tenant-scoped
 * only, not Company-scoped within a Tenant - the spec's own data model
 * defines Membership as "Join of User + Tenant + Role," with no
 * per-Company variation described anywhere.
 *
 * `Tenant` already references `MembershipId`s by reference (its
 * `_adminMembershipIds` set, built alongside `Tenant` itself) - this is
 * the aggregate those IDs actually point to, filling in the piece
 * Section 9.6 flagged as not yet built.
 */
class Membership private constructor(
    val id: MembershipId,
    val userId: UserId,
    val tenantId: TenantId,
    val role: Role
) {
    var status: MembershipStatus = MembershipStatus.ACTIVE
        private set

    fun revoke(): ValidationResult = transitionTo(MembershipStatus.REVOKED)

    fun reactivate(): ValidationResult = transitionTo(MembershipStatus.ACTIVE)

    private fun transitionTo(newStatus: MembershipStatus): ValidationResult {
        if (!status.canTransitionTo(newStatus)) {
            return ValidationResult.failure("Cannot transition Membership from $status to $newStatus")
        }
        status = newStatus
        return ValidationResult.success()
    }

    companion object {
        fun grant(
            userId: UserId,
            tenantId: TenantId,
            role: Role,
            id: MembershipId = MembershipId.generate()
        ): Membership = Membership(id, userId, tenantId, role)

        /**
         * Rebuilds an already-valid Membership from persisted state
         * (Section 10) - needed because [grant] always starts a Membership
         * at ACTIVE; a reloaded REVOKED Membership has to bypass that.
         * `internal`, matches the repository-only visibility precedent.
         */
        internal fun reconstitute(
            id: MembershipId,
            userId: UserId,
            tenantId: TenantId,
            role: Role,
            status: MembershipStatus
        ): Membership {
            val membership = Membership(id, userId, tenantId, role)
            membership.status = status
            return membership
        }
    }
}
