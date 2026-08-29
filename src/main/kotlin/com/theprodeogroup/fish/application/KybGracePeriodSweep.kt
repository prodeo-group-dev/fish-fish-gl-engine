package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import java.time.Instant

/**
 * The scheduled application-service sweep from docs/DDD_Design.md
 * Section 9.4 - finds every `Active` Tenant whose `kybVerificationDeadline`
 * has passed with `kybStatus`, `adminKycStatus`, or `adminPhoneVerificationStatus`
 * still not `Verified` (2026-08-27: admin phone joined the other two as
 * part of KYB, but keeps its own much tighter 14-day
 * `phoneVerificationDeadline` - see `Tenant.isPhoneVerificationOverdue`),
 * and suspends it (`Tenant.suspendForExpiredKyb()`). Deliberately named
 * `Sweep`, not `...UseCase` - unlike `OnboardTenantUseCase`/
 * `AddCompanyToTenantUseCase`, this isn't triggered by a single caller
 * action with a `Request`; it's meant to run on a recurring schedule
 * (e.g. daily) against whatever Tenants currently qualify.
 *
 * **Scope, deliberately narrow**: only Section 9.4's "Deadline
 * enforcement" bullet - the actual `Active -> Suspended` transition. The
 * "Reminders" bullet (scheduled prompts at day 90/150/166/173/179) is a
 * genuinely separate mechanism - it needs a way to actually deliver a
 * notification (email/SMS/in-app), and nothing resembling that exists
 * anywhere in this codebase yet. Not built here; a real gap, not silently
 * dropped.
 *
 * **The eligibility check stays in the domain, not duplicated here.**
 * `Tenant.isKybGracePeriodExpired(now)` already encodes the full rule
 * (status must be Active, at least one of the two verification fields
 * not yet Verified, and past the deadline) - this class's only job is to
 * enumerate candidates (`TenantRepository.findAllActive()`) and ask each
 * one, matching Section 9.4's own framing: "a scheduled application-service
 * sweep... same 'domain service checks and validates' pattern."
 *
 * **Returns the domain events each suspended Tenant raises**
 * (`KybGracePeriodExpired`), per the "aggregate collects, application
 * service publishes" pattern (Section 8) - same reasoning as
 * `OnboardTenantUseCase.Result.events`. No event bus/publisher exists in
 * this codebase yet, so the caller (whatever schedules this sweep) gets
 * them back to do with as it sees fit.
 *
 * **No cross-repository atomicity, matching the established policy**
 * (Section 10.5/10.6) - each Tenant is saved independently as it's
 * suspended, not as one batch transaction. If the sweep is interrupted
 * partway through a run, the Tenants already processed stay suspended;
 * the rest remain candidates for the next run. This is actually the
 * *correct* behavior for a recurring sweep, not just an accepted
 * tradeoff - an idempotent job that picks up where it left off next run
 * is preferable to an all-or-nothing batch that would need to restart
 * from scratch.
 */
class KybGracePeriodSweep(
    private val tenantRepository: TenantRepository
) {
    data class SuspendedTenant(val tenant: Tenant, val events: List<DomainEvent>)
    data class Result(val suspended: List<SuspendedTenant>)

    fun run(now: Instant = Instant.now()): Result {
        val suspended = mutableListOf<SuspendedTenant>()

        for (tenant in tenantRepository.findAllActive()) {
            if (!tenant.isKybGracePeriodExpired(now) && !tenant.isPhoneVerificationOverdue(now)) continue

            val suspension = tenant.suspendForExpiredKyb(now)
            check(suspension.isValid) {
                "KybGracePeriodSweep found a Tenant eligible via isKybGracePeriodExpired() " +
                    "that suspendForExpiredKyb() then rejected: ${suspension.errors.joinToString()}"
            }

            tenantRepository.save(tenant)
            suspended.add(SuspendedTenant(tenant, tenant.pullDomainEvents()))
        }

        return Result(suspended)
    }
}
