package com.theprodeogroup.fish.domain.tenancy

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.ValidationResult
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
 * [recordKybOutcome] populate it, and [activate] performs the same checks
 * as Section 9.2 step 7 before flipping it live.
 *
 * KYB verification is deliberately not part of [TenantStatus] - see
 * [isKybGracePeriodExpired] and the 180-day grace period it implements
 * (Section 9.4).
 */
class Tenant private constructor(
    val id: TenantId,
    val name: String,
    val segment: TenantSegment,
    val baseCurrency: Currency
) {
    var status: TenantStatus = TenantStatus.DRAFT
        private set

    var kybStatus: KybStatus = KybStatus.PENDING
        private set

    var kybVerificationDeadline: Instant? = null
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
     * Section 9.2 step 5. Recording an outcome never fails - FiSH doesn't
     * perform the check, only records whatever the external result was,
     * even after activation (a later Flagged result is what the arrears/
     * suspension workflow reacts to).
     */
    fun recordKybOutcome(newStatus: KybStatus) {
        kybStatus = newStatus
    }

    /**
     * Section 9.2 step 7. Only a Draft tenant can be activated this way -
     * a Suspended tenant must go through [reactivate] instead. Resolved
     * per Section 9.2: a Pending KYB status does not block this; only
     * Flagged does. On success, starts the 180-day KYB grace-period clock
     * (Section 9.4) and raises both TenantActivated and TenantOnboarded -
     * this call is literally steps 7 and 8 of OnboardTenantUseCase.
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

        val result = statusCheck.combine(companyCheck).combine(membershipCheck).combine(kybCheck)
        if (!result.isValid) return result

        status = TenantStatus.ACTIVE
        kybVerificationDeadline = now.plus(KYB_GRACE_PERIOD_DAYS, ChronoUnit.DAYS)
        _domainEvents.add(TenantActivated(id, now))
        _domainEvents.add(TenantOnboarded(id, now))
        return ValidationResult.success()
    }

    /** Reversible - non-payment, a KYB flag raised post-activation, or [suspendForExpiredKyb]. */
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
     * True once an Active tenant is past [kybVerificationDeadline] with KYB
     * still not Verified - what the scheduled sweep in Section 9.4 checks
     * for. Never true for a tenant that was never activated (no deadline
     * set) or one that's already Verified/no longer Active.
     */
    fun isKybGracePeriodExpired(asOf: Instant = Instant.now()): Boolean {
        val deadline = kybVerificationDeadline ?: return false
        return status == TenantStatus.ACTIVE && kybStatus != KybStatus.VERIFIED && asOf.isAfter(deadline)
    }

    /**
     * The scheduled sweep's action (Section 9.4): automated suspension
     * distinct from [suspend], raising [KybGracePeriodExpired] instead of
     * [TenantSuspended] so billing/notifications can tell them apart.
     */
    fun suspendForExpiredKyb(now: Instant = Instant.now()): ValidationResult {
        if (!isKybGracePeriodExpired(now)) {
            return ValidationResult.failure("KYB grace period has not expired, or tenant is not eligible for automated suspension")
        }
        status = TenantStatus.SUSPENDED
        _domainEvents.add(KybGracePeriodExpired(id, now))
        return ValidationResult.success()
    }

    companion object {
        const val KYB_GRACE_PERIOD_DAYS = 180L

        /** Section 9.2 steps 1-2: capture identity, create in Draft. No Companies/Memberships yet. */
        fun onboard(
            name: String,
            segment: TenantSegment,
            baseCurrency: Currency,
            id: TenantId = TenantId.generate()
        ): Tenant = Tenant(id, name, segment, baseCurrency)
    }
}
