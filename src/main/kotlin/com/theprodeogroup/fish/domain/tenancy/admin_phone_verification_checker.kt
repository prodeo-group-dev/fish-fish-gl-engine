package com.theprodeogroup.fish.domain.tenancy

/**
 * Port for confirming a phone number was genuinely verified by the
 * external IdP (Cognito) before [RecordAdminPhoneNumberUseCase] records
 * it as such on a [Tenant] - same "FiSH records, never performs the
 * check" boundary [Tenant.recordAdminKycOutcome] already draws for KYC,
 * except here there's a real, checkable fact behind it (Cognito's own
 * `phone_number_verified` attribute), so the use case checks it rather
 * than trusting whatever the caller's own request body claims.
 *
 * `email`, not a Cognito username/sub - matches how [Auth.installFishJwtAuth]
 * already resolves every other identity in this codebase (the JWT's
 * `email` claim), and Cognito's `AdminGetUser` accepts any alias
 * (including email, since `username_attributes = ["email"]`) as well as
 * the real username.
 */
interface AdminPhoneVerificationChecker {
    fun isVerified(email: String, phoneNumber: PhoneNumber): Boolean
}
