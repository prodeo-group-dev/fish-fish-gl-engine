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
 *
 * **[role] and [accessLevel] are genuinely independent fields
 * (2026-08-29)**, not one derived from the other - see [AccessLevel]'s
 * own KDoc for why. [grant]'s [accessLevel] parameter defaults to
 * [defaultAccessLevelFor]'s mapping purely as a convenience for the
 * common case (a caller who only cares about `role` gets the access
 * level that role has always implied); any caller that needs to grant
 * a Role a *different* AccessLevel than its default - an ACCOUNTANT
 * with only READ, say - passes both explicitly.
 */
class Membership private constructor(
    val id: MembershipId,
    val userId: UserId,
    val tenantId: TenantId,
    val role: Role,
    val accessLevel: AccessLevel
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
            accessLevel: AccessLevel = defaultAccessLevelFor(role),
            id: MembershipId = MembershipId.generate()
        ): Membership = Membership(id, userId, tenantId, role, accessLevel)

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
            status: MembershipStatus,
            accessLevel: AccessLevel
        ): Membership {
            val membership = Membership(id, userId, tenantId, role, accessLevel)
            membership.status = status
            return membership
        }

        /**
         * [grant]'s convenience default only - not an authoritative rule,
         * see [Membership]'s own KDoc. Mirrors what [role] has always
         * implied in practice before [AccessLevel] existed as its own
         * field: [Role.OWNER_ADMIN] full control, [Role.ACCOUNTANT]
         * ordinary posting rights, [Role.APPROVER] the approval step
         * above that, [Role.READ_ONLY] exactly what its name says, and
         * [Role.COMPLIANCE_ETHICS_REVIEW] a reviewing/oversight function -
         * read access by default, escalated explicitly per-grant if a
         * specific compliance workflow needs to approve something.
         */
        private fun defaultAccessLevelFor(role: Role): AccessLevel = when (role) {
            Role.OWNER_ADMIN -> AccessLevel.ADMIN
            Role.ACCOUNTANT -> AccessLevel.WRITE
            Role.APPROVER -> AccessLevel.APPROVE
            Role.READ_ONLY -> AccessLevel.READ
            Role.COMPLIANCE_ETHICS_REVIEW -> AccessLevel.READ
        }
    }
}
