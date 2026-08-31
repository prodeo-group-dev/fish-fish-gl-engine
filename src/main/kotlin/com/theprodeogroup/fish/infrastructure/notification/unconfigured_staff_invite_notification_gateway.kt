package com.theprodeogroup.fish.infrastructure.notification

import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationGateway
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationResult

/**
 * Used whenever `GL_STAFF_INVITE_FROM_EMAIL` isn't set for this
 * deployment - fails every call rather than silently no-oping, so a
 * caller sees exactly *why* no email went out instead of a generic
 * success. Unlike POP's own `UnconfiguredOrderNotificationGateway`
 * (written while SES production access was still DENIED), this isn't
 * an account-level block - SES production access was approved
 * 2026-08-31 (case 178782151300385) - it's purely a "not wired up for
 * this environment yet" gap, same shape as any other unset
 * environment-configured dependency in this codebase.
 *
 * `InviteStaffMemberUseCase` never fails on this - see its own KDoc -
 * the invited person's own account is created immediately either way,
 * matching the user's explicit direction: "manual is available as
 * backup."
 */
class UnconfiguredStaffInviteNotificationGateway : StaffInviteNotificationGateway {
    override fun sendInviteEmail(to: String, tenantName: String, inviterName: String, role: Role): StaffInviteNotificationResult =
        StaffInviteNotificationResult.Failure(
            "Staff-invite email is not configured for this deployment - GL_STAFF_INVITE_FROM_EMAIL is unset"
        )
}
