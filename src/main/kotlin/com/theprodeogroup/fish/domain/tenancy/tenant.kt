package com.theprodeogroup.fish.domain.tenancy

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.common.ValidationResult
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * A customer organization using FiSH; the top-level data-isolation boundary
 * (docs/DDD_Design.md Section 1/3.2).
 *
 * Owns Companies and admin Memberships by reference (ID only - Company and
 * Membership are their own aggregate roots, never embedded here). Built to
 * match the OnboardTenantUseCase flow in docs/DDD_Design.md Section 9:
 * [onboard] creates a bare Draft tenant, [addCompany]/[addAdminMembership]/
 * [recordKybOutcome]/[recordAdminKycOutcome] populate it, and [activate]
 * performs the same checks as Section 9.2 step 7 before flipping it live.
 *
 * Verification is deliberately not part of [TenantStatus] - see
 * [isKybGracePeriodExpired] and the 180-day grace period it implements
 * (Section 9.4), which covers both [kybStatus] and [adminKycStatus].
 */
class Tenant private constructor(
    val id: TenantId,
    val name: String,
    val segment: TenantSegment,
    val baseCurrency: Currency
) {
    var status: TenantStatus = TenantStatus.DRAFT
        private set

    /** Business-level KYB check outcome. */
    var kybStatus: VerificationStatus = VerificationStatus.PENDING
        private set

    /**
     * Personal KYC outcome for the admin User created at onboarding
     * (Section 9.2 step 4) - tracked separately from [kybStatus] since they
     * can clear at different times or come from different verification
     * providers, but share the same activation rule and grace-period
     * deadline (Section 9.4).
     */
    var adminKycStatus: VerificationStatus = VerificationStatus.PENDING
        private set

    /** Shared 180-day deadline for both [kybStatus] and [adminKycStatus] to reach VERIFIED. */
    var kybVerificationDeadline: Instant? = null
        private set

    /**
     * The founding admin's mobile number - part of KYB, not a separate
     * verification track (2026-08-27 requirement: a verified admin phone
     * number closes the same "who controls this business" gap
     * [adminKycStatus]'s own KDoc describes, just via a channel that's
     * fast to supply and fast to verify). Null until [recordAdminPhoneNumber]
     * is called - unlike [kybStatus]/[adminKycStatus], there's no
     * "outcome of a check FiSH didn't perform" to default to, since no
     * check has been requested until a number exists to check.
     */
    var adminPhoneNumber: PhoneNumber? = null
        private set

    /** Outcome of verifying [adminPhoneNumber] - PENDING until a number is recorded, then whatever Cognito's own SMS verification reports. */
    var adminPhoneVerificationStatus: VerificationStatus = VerificationStatus.PENDING
        private set

    /**
     * Deadline for [adminPhoneVerificationStatus] to reach VERIFIED -
     * deliberately much tighter than [kybVerificationDeadline] (14 days,
     * not 180): supplying and verifying a phone number is fast enough
     * that it doesn't need the same runway as a full KYB check, and
     * catching a missing phone number early is the whole point of
     * giving it its own shorter clock rather than letting it hide
     * inside the 180-day one. Still the same consequence either way -
     * see [isKybGracePeriodExpired].
     */
    var phoneVerificationDeadline: Instant? = null
        private set

    private val _companyIds = mutableSetOf<CompanyId>()
    val companyIds: Set<CompanyId> get() = _companyIds.toSet()

    private val _adminMembershipIds = mutableSetOf<MembershipId>()
    val adminMembershipIds: Set<MembershipId> get() = _adminMembershipIds.toSet()

    private val _domainEvents = mutableListOf<DomainEvent>()

    /**
     * Drains and returns the events raised since the last call - per
     * docs/DDD_Design.md Section 8's "aggregate collects, application
     * service publishes" pattern. Calling this twice in a row returns an
     * empty list the second time.
     */
    fun pullDomainEvents(): List<DomainEvent> {
        val events = _domainEvents.toList()
        _domainEvents.clear()
        return events
    }

    /** Section 9.2 step 3. Rejected once the tenant is Closed. */
    fun addCompany(companyId: CompanyId): ValidationResult {
        if (status == TenantStatus.CLOSED) {
            return ValidationResult.failure("Cannot add a Company to a Closed tenant")
        }
        _companyIds.add(companyId)
        return ValidationResult.success()
    }

    /** Section 9.2 step 4. Rejected once the tenant is Closed. */
    fun addAdminMembership(membershipId: MembershipId): ValidationResult {
        if (status == TenantStatus.CLOSED) {
            return ValidationResult.failure("Cannot add a Membership to a Closed tenant")
        }
        _adminMembershipIds.add(membershipId)
        return ValidationResult.success()
    }

    /**
     * Section 9.2 step 5 (business half). Recording an outcome never fails -
     * FiSH doesn't perform the check, only records whatever the external
     * result was, even after activation (a later Flagged result is what the
     * arrears/suspension workflow reacts to).
     */
    fun recordKybOutcome(newStatus: VerificationStatus) {
        kybStatus = newStatus
    }

    /**
     * Section 9.2 step 5 (admin-KYC half) - the founding admin's personal
     * identity check, part of the same onboarding KYB step, not the later
     * per-member declaration (spec Section 7.11). Recording an outcome
     * never fails, same rationale as [recordKybOutcome].
     */
    fun recordAdminKycOutcome(newStatus: VerificationStatus) {
        adminKycStatus = newStatus
    }

    /**
     * Records the founding admin's mobile number once they supply it
     * (necessarily after [activate] - see [adminPhoneNumber]'s own KDoc
     * on why there's nothing to record before then). Recording a number
     * again (e.g. correcting a typo) resets [adminPhoneVerificationStatus]
     * back to PENDING - a previously-verified OLD number does not carry
     * over to a new one that hasn't itself been verified yet.
     */
    fun recordAdminPhoneNumber(phoneNumber: PhoneNumber) {
        adminPhoneNumber = phoneNumber
        adminPhoneVerificationStatus = VerificationStatus.PENDING
    }

    /**
     * Records the outcome of verifying [adminPhoneNumber] - same
     * "FiSH records, never performs the check" rationale as
     * [recordAdminKycOutcome], except here the check is Cognito's own
     * SMS verification, not an external KYC provider.
     */
    fun recordAdminPhoneVerificationOutcome(newStatus: VerificationStatus) {
        adminPhoneVerificationStatus = newStatus
    }

    /**
     * Section 9.2 step 7. Only a Draft tenant can be activated this way -
     * a Suspended tenant must go through [reactivate] instead. Resolved
     * per Section 9.2: Pending kybStatus/adminKycStatus does not block
     * this; Flagged on either does. On success, starts the shared 180-day
     * grace-period clock (Section 9.4) and raises both TenantActivated and
     * TenantOnboarded - this call is literally steps 7 and 8 of
     * OnboardTenantUseCase.
     */
    fun activate(now: Instant = Instant.now()): ValidationResult {
        val statusCheck = if (status == TenantStatus.DRAFT) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Only a Draft tenant can be activated; status is currently $status")
        }
        val companyCheck = if (_companyIds.isNotEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Tenant must have at least one Company before activation")
        }
        val membershipCheck = if (_adminMembershipIds.isNotEmpty()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Tenant must have at least one admin Membership before activation")
        }
        val kybCheck = if (!kybStatus.blocksActivation()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Tenant KYB status is Flagged - cannot activate")
        }
        val adminKycCheck = if (!adminKycStatus.blocksActivation()) {
            ValidationResult.success()
        } else {
            ValidationResult.failure("Admin KYC status is Flagged - cannot activate")
        }

        val result = statusCheck.combine(companyCheck).combine(membershipCheck)
            .combine(kybCheck).combine(adminKycCheck)
        if (!result.isValid) return result

        status = TenantStatus.ACTIVE
        kybVerificationDeadline = now.plus(KYB_GRACE_PERIOD_DAYS, ChronoUnit.DAYS)
        phoneVerificationDeadline = now.plus(PHONE_VERIFICATION_GRACE_PERIOD_DAYS, ChronoUnit.DAYS)
        _domainEvents.add(TenantActivated(id, now))
        _domainEvents.add(TenantOnboarded(id, now))
        return ValidationResult.success()
    }

    /** Reversible - non-payment, a KYB/KYC flag raised post-activation, or [suspendForExpiredKyb]. */
    fun suspend(reason: String, now: Instant = Instant.now()): ValidationResult {
        if (!status.canTransitionTo(TenantStatus.SUSPENDED)) {
            return ValidationResult.failure("Cannot suspend a tenant in status $status")
        }
        status = TenantStatus.SUSPENDED
        _domainEvents.add(TenantSuspended(id, reason, now))
        return ValidationResult.success()
    }

    /** Only a Suspended tenant can reactivate - use [activate] for the initial Draft -> Active transition. */
    fun reactivate(now: Instant = Instant.now()): ValidationResult {
        if (status != TenantStatus.SUSPENDED) {
            return ValidationResult.failure("Only a Suspended tenant can be reactivated; status is currently $status")
        }
        status = TenantStatus.ACTIVE
        _domainEvents.add(TenantReactivated(id, now))
        return ValidationResult.success()
    }

    /** Terminal. Data is retained per spec Section 7.15, never deleted on close. */
    fun close(now: Instant = Instant.now()): ValidationResult {
        if (!status.canTransitionTo(TenantStatus.CLOSED)) {
            return ValidationResult.failure("Cannot close a tenant in status $status")
        }
        status = TenantStatus.CLOSED
        _domainEvents.add(TenantClosed(id, now))
        return ValidationResult.success()
    }

    /**
     * True once an Active tenant is past [kybVerificationDeadline] with
     * [kybStatus], [adminKycStatus], or [adminPhoneVerificationStatus]
     * still not Verified - the phone number is part of KYB (2026-08-27),
     * not a separate track, so it counts here alongside the other two,
     * even though [isPhoneVerificationOverdue] below will almost always
     * catch a missing phone number first, given its much shorter deadline.
     * Never true for a tenant that was never activated (no deadline set)
     * or one that's already fully Verified/no longer Active.
     */
    fun isKybGracePeriodExpired(asOf: Instant = Instant.now()): Boolean {
        val deadline = kybVerificationDeadline ?: return false
        return status == TenantStatus.ACTIVE && !isFullyVerified() && asOf.isAfter(deadline)
    }

    /**
     * True once an Active tenant is past [phoneVerificationDeadline] with
     * [adminPhoneVerificationStatus] still not Verified - the 14-day
     * sub-check within KYB (see [phoneVerificationDeadline]'s own KDoc
     * on why it's tighter than [kybVerificationDeadline]). Never true for
     * a tenant that hasn't been activated yet (no deadline set).
     */
    fun isPhoneVerificationOverdue(asOf: Instant = Instant.now()): Boolean {
        val deadline = phoneVerificationDeadline ?: return false
        return status == TenantStatus.ACTIVE &&
            adminPhoneVerificationStatus != VerificationStatus.VERIFIED &&
            asOf.isAfter(deadline)
    }

    private fun isFullyVerified(): Boolean =
        kybStatus == VerificationStatus.VERIFIED &&
            adminKycStatus == VerificationStatus.VERIFIED &&
            adminPhoneVerificationStatus == VerificationStatus.VERIFIED

    /**
     * The scheduled sweep's action (Section 9.4): automated suspension
     * distinct from [suspend], raising [KybGracePeriodExpired] instead of
     * [TenantSuspended] so billing/notifications can tell them apart.
     * Triggered by either [isKybGracePeriodExpired] (the 180-day check) or
     * [isPhoneVerificationOverdue] (the 14-day one) - both are KYB
     * non-compliance, just on different clocks, so both raise the same
     * event rather than needing a phone-specific one.
     */
    fun suspendForExpiredKyb(now: Instant = Instant.now()): ValidationResult {
        if (!isKybGracePeriodExpired(now) && !isPhoneVerificationOverdue(now)) {
            return ValidationResult.failure("KYB/KYC/phone-verification grace period has not expired, or tenant is not eligible for automated suspension")
        }
        status = TenantStatus.SUSPENDED
        _domainEvents.add(KybGracePeriodExpired(id, now))
        return ValidationResult.success()
    }

    companion object {
        const val KYB_GRACE_PERIOD_DAYS = 180L
        const val PHONE_VERIFICATION_GRACE_PERIOD_DAYS = 14L

        /** Section 9.2 steps 1-2: capture identity, create in Draft. No Companies/Memberships yet. */
        fun onboard(
            name: String,
            segment: TenantSegment,
            baseCurrency: Currency,
            id: TenantId = TenantId.generate()
        ): Tenant = Tenant(id, name, segment, baseCurrency)

        /**
         * Rebuilds an already-valid Tenant from persisted state (Section 10) -
         * bypasses [onboard]'s Draft-only starting point the same way
         * `Account`/`Period`/`JournalEntry.reconstitute()` bypass their own
         * `create()` validation. `internal` visibility only - a repository
         * concern, not part of the aggregate's public API. [companyIds] and
         * [adminMembershipIds] repopulate the private sets directly, since
         * they're genuinely part of Tenant's own state (Section 9.2 steps
         * 3-4), not derived from Company/Membership's own `tenantId`.
         */
        internal fun reconstitute(
            id: TenantId,
            name: String,
            segment: TenantSegment,
            baseCurrency: Currency,
            status: TenantStatus,
            kybStatus: VerificationStatus,
            adminKycStatus: VerificationStatus,
            kybVerificationDeadline: Instant?,
            companyIds: Set<CompanyId>,
            adminMembershipIds: Set<MembershipId>,
            adminPhoneNumber: PhoneNumber? = null,
            adminPhoneVerificationStatus: VerificationStatus = VerificationStatus.PENDING,
            phoneVerificationDeadline: Instant? = null
        ): Tenant {
            val tenant = Tenant(id, name, segment, baseCurrency)
            tenant.status = status
            tenant.kybStatus = kybStatus
            tenant.adminKycStatus = adminKycStatus
            tenant.kybVerificationDeadline = kybVerificationDeadline
            tenant._companyIds.addAll(companyIds)
            tenant._adminMembershipIds.addAll(adminMembershipIds)
            tenant.adminPhoneNumber = adminPhoneNumber
            tenant.adminPhoneVerificationStatus = adminPhoneVerificationStatus
            tenant.phoneVerificationDeadline = phoneVerificationDeadline
            return tenant
        }
    }
}
