package com.theprodeogroup.fish.infrastructure.notification

import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationGateway
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationResult
import software.amazon.awssdk.services.sesv2.SesV2Client
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SesV2Exception

/**
 * Real staff-invite email delivery via SES (2026-08-31) - GL Engine's
 * first own outbound email (Cognito already sends its own
 * verification/reset emails natively; POP separately sends eOrder
 * email via its own `SesOrderNotificationGateway`, the direct pattern
 * this mirrors). SES production access is already approved account-wide
 * (case 178782151300385, 2026-08-31) - this only needs its own verified
 * sender identity and IAM permission wired in, not a fresh SES request.
 *
 * Credentials/region come from [SesV2Client.create]'s default chain -
 * ECS's own task role and Fargate-injected `AWS_REGION`, same pattern
 * this codebase's `CognitoIdentityProviderClient` already uses.
 * [fromEmail] must be a verified SES sender identity - `notifications.tf`'s
 * own task definition supplies it via `GL_STAFF_INVITE_FROM_EMAIL`.
 */
class SesStaffInviteNotificationGateway(private val fromEmail: String) : StaffInviteNotificationGateway {
    private val client: SesV2Client = SesV2Client.create()

    override fun sendInviteEmail(to: String, tenantName: String, inviterName: String, role: Role): StaffInviteNotificationResult {
        val subject = "$inviterName added you to $tenantName on FiSH"
        val body = "$inviterName has given you ${role.name.lowercase().replace('_', ' ')} access to " +
            "$tenantName on FiSH. Sign up (or sign in, if you already have an account) at " +
            "capital.theprodeogroup.com using this email address ($to) to get started."

        return try {
            val request = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(Destination.builder().toAddresses(to).build())
                .content(
                    EmailContent.builder()
                        .simple(
                            Message.builder()
                                .subject(Content.builder().data(subject).charset("UTF-8").build())
                                .body(Body.builder().text(Content.builder().data(body).charset("UTF-8").build()).build())
                                .build()
                        )
                        .build()
                )
                .build()

            val response = client.sendEmail(request)
            StaffInviteNotificationResult.Success(response.messageId())
        } catch (e: SesV2Exception) {
            StaffInviteNotificationResult.Failure(e.awsErrorDetails()?.errorMessage() ?: e.message ?: "SES send failed")
        }
    }
}
