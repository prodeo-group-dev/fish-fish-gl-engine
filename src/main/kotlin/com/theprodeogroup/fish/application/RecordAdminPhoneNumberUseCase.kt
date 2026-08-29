package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.tenancy.AdminPhoneVerificationChecker
import com.theprodeogroup.fish.domain.tenancy.PhoneNumber
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.VerificationStatus

/**
 * The 2026-08-27 KYB extension's write path: lets a signed-in admin
 * record their own mobile number against their Tenant once Cognito has
 * actually verified it (an SMS code sent/confirmed entirely within
 * Cognito - see [AdminPhoneVerificationChecker]'s own KDoc on why this
 * use case checks that fact rather than trusting the request body).
 *
 * Deliberately rejects an unverified number outright rather than
 * recording it as PENDING and letting a later step verify it - unlike
 * [Tenant.recordKybOutcome]/[Tenant.recordAdminKycOutcome] (external
 * checks FiSH has no way to trigger itself, so recording an outcome
 * ahead of time makes sense), the phone verification flow already
 * finished client-side by the time this call happens; there's no
 * "PENDING, verify later" state worth persisting for a check that
 * either already succeeded or didn't.
 */
class RecordAdminPhoneNumberUseCase(
    private val tenantRepository: TenantRepository,
    private val phoneVerificationChecker: AdminPhoneVerificationChecker
) {
    sealed class Result {
        data class Success(val tenant: Tenant) : Result()
        data object TenantNotFound : Result()
        data object NotVerifiedByCognito : Result()
    }

    fun execute(tenantId: TenantId, callerEmail: String, phoneNumber: PhoneNumber): Result {
        val tenant = tenantRepository.findById(tenantId) ?: return Result.TenantNotFound

        if (!phoneVerificationChecker.isVerified(callerEmail, phoneNumber)) {
            return Result.NotVerifiedByCognito
        }

        tenant.recordAdminPhoneNumber(phoneNumber)
        tenant.recordAdminPhoneVerificationOutcome(VerificationStatus.VERIFIED)
        tenantRepository.save(tenant)

        return Result.Success(tenant)
    }
}
