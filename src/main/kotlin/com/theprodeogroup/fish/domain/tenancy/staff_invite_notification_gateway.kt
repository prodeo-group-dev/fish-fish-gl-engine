package com.theprodeogroup.fish.domain.tenancy

/** Outcome of a [StaffInviteNotificationGateway] call - mirrors the Result shape POP's own `OrderNotificationGateway` uses. */
sealed class StaffInviteNotificationResult {
    data class Success(val providerReference: String?) : StaffInviteNotificationResult()
    data class Failure(val reason: String) : StaffInviteNotificationResult()
}

/**
 * Notifies an invited staff member by email (2026-08-31, "Onboarding
 * process needs to be completed - Business Staff onboarding" - user's
 * own explicit direction: "if email is supplied we use the email but
 * manual is available as backup"). An interface, not a concrete AWS
 * SDK call, matching this codebase's own established
 * repository-interface-at-the-boundary pattern (mirrors POP's
 * `OrderNotificationGateway`).
 *
 * Deliberately best-effort from [InviteStaffMemberUseCase]'s own
 * perspective - a [StaffInviteNotificationResult.Failure] here never
 * fails the invite itself. The `Membership` granting real access is
 * the part that actually matters; the email is a courtesy with an
 * explicit manual fallback ("tell them yourself") by design, not a
 * required step.
 *
 * Plain (not `suspend`) - every use case in this codebase's
 * `application` layer is a synchronous `fun execute(...)`, and the
 * underlying AWS SES SDK call is blocking by nature anyway (matches
 * `SesV2Client`'s own synchronous client, the same one POP's gateway
 * wraps). Keeping this plain avoids introducing `suspend` into GL's
 * use-case layer for the first time over what's still a fire-and-check
 * side effect, not genuine async orchestration.
 */
interface StaffInviteNotificationGateway {
    fun sendInviteEmail(to: String, tenantName: String, inviterName: String, role: Role): StaffInviteNotificationResult
}
