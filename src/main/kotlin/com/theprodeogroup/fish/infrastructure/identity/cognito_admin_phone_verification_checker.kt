package com.theprodeogroup.fish.infrastructure.identity

import com.theprodeogroup.fish.domain.tenancy.AdminPhoneVerificationChecker
import com.theprodeogroup.fish.domain.tenancy.PhoneNumber
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException

/**
 * Real [AdminPhoneVerificationChecker] - calls Cognito's `AdminGetUser`
 * (read-only; the ECS task role's own IAM policy is scoped to exactly
 * this one action, see infra/terraform/notifications.tf) and checks
 * both that [PhoneNumber] matches what Cognito has on file AND that
 * Cognito's own `phone_number_verified` attribute is `"true"` - a
 * number recorded but not yet SMS-confirmed doesn't count.
 *
 * Credentials come from [CognitoIdentityProviderClient.create]'s
 * default provider chain (the ECS task role's temporary credentials at
 * runtime) - nothing to configure here beyond `FISH_COGNITO_USER_POOL_ID`.
 */
class CognitoAdminPhoneVerificationChecker(
    private val userPoolId: String,
    private val client: CognitoIdentityProviderClient = CognitoIdentityProviderClient.create()
) : AdminPhoneVerificationChecker {

    override fun isVerified(email: String, phoneNumber: PhoneNumber): Boolean {
        val response = try {
            client.adminGetUser(
                AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .build()
            )
        } catch (e: UserNotFoundException) {
            return false
        }

        val attributes = response.userAttributes().associate { it.name() to it.value() }
        return attributes["phone_number"] == phoneNumber.value && attributes["phone_number_verified"] == "true"
    }
}
