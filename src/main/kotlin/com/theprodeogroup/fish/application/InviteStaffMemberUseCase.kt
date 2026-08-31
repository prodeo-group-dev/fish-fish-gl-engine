package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.MembershipStatus
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationGateway
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationResult
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.domain.tenancy.UserRepository

/**
 * Adds a staff member to an existing, already-onboarded Tenant
 * (2026-08-31 - the onboarding flow's other half, alongside
 * [OnboardTenantUseCase]'s "brand-new Tenant" path; the user's own
 * framing was "Business Owner onboarding" vs. "Business Staff
 * onboarding"). Authorization (only an ADMIN-access-level Membership
 * may invite) is the route layer's job - see `authorizeTenantForAdmin`
 * in `Auth.kt` - the same division of responsibility
 * [AddCompanyToTenantUseCase]'s own route already uses for its own
 * authorization check.
 *
 * **No separate "pending invite" state.** `Membership.grant()` always
 * starts `ACTIVE` - there is no `PENDING` value in [MembershipStatus] -
 * so this genuinely *grants* access immediately, it doesn't queue a
 * request for later acceptance. The invited person doesn't need to do
 * anything to be let in: they just need to sign up (or already have an
 * account) under the exact email invited, and GL's own JWT auth
 * (`Auth.kt`'s `FISH_JWT_AUTH_NAME` validate block, which resolves
 * identity via `UserRepository.findByEmail`) picks up the Membership on
 * their very next authenticated call - no separate staff-onboarding
 * wizard needed on the frontend at all.
 *
 * **Finds-or-creates the `User`** by email, since the invited address
 * may already be a `User` elsewhere (a different Tenant) or may never
 * have signed up anywhere yet - both are valid starting points.
 *
 * **Idempotent, not additive, on repeat invites**: re-inviting an email
 * already ACTIVE in this Tenant returns that same Membership rather
 * than granting a duplicate; a previously-REVOKED Membership is
 * reactivated instead, since [MembershipRepository] has no natural
 * per-(user, tenant) uniqueness constraint to lean on instead. A
 * changed `role` on a repeat invite is deliberately NOT applied here -
 * that would be a silent role change riding along on what looks like a
 * re-invite; changing an existing member's role is a distinct action
 * this use case doesn't attempt.
 */
class InviteStaffMemberUseCase(
    private val tenantRepository: TenantRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val notificationGateway: StaffInviteNotificationGateway
) {
    data class Request(
        val tenantId: TenantId,
        val email: String,
        val name: String,
        val role: Role,
        val inviterName: String,
        /**
         * Which of GL/HR/SOP/POP/IM this invite grants (2026-08-31,
         * "permissions may be given to some staff/employees to run the
         * FiSH modules"). Deliberately no default here, unlike
         * [Membership.grant]'s own "every module" default for a fresh
         * grant elsewhere (e.g. an onboarding admin) - an *invite*
         * should always be a deliberate choice of what the invitee can
         * see, not an accidental grant-everything via an omitted field.
         */
        val modules: Set<ManagedModule>
    )

    sealed class Result {
        data class Invited(
            val user: User,
            val membership: Membership,
            val alreadyMember: Boolean,
            val notification: StaffInviteNotificationResult
        ) : Result()

        object TenantNotFound : Result()
    }

    fun execute(request: Request): Result {
        val tenant = tenantRepository.findById(request.tenantId) ?: return Result.TenantNotFound

        var user = userRepository.findByEmail(request.email)
        if (user == null) {
            user = User.create(request.email, request.name)
            userRepository.save(user)
        }

        val existingMembership = membershipRepository.findAllByUser(user.id).firstOrNull { it.tenantId == tenant.id }

        val (membership, alreadyMember) = if (existingMembership != null) {
            val wasActive = existingMembership.status == MembershipStatus.ACTIVE
            if (!wasActive) {
                val reactivation = existingMembership.reactivate()
                check(reactivation.isValid) {
                    "InviteStaffMemberUseCase tried to reactivate a Membership that rejected the transition: ${reactivation.errors.joinToString()}"
                }
            }
            membershipRepository.save(existingMembership)
            existingMembership to wasActive
        } else {
            val granted = Membership.grant(user.id, tenant.id, request.role, grantedModules = request.modules)
            membershipRepository.save(granted)
            granted to false
        }

        val notification = notificationGateway.sendInviteEmail(request.email, tenant.name, request.inviterName, request.role)

        return Result.Invited(user, membership, alreadyMember, notification)
    }
}
