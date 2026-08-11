package com.theprodeogroup.fish.domain.tenancy

import com.theprodeogroup.fish.domain.common.DomainEvent
import java.time.Instant

/**
 * Domain events raised by the Tenant aggregate, per docs/DDD_Design.md
 * Section 4/9. Small and related enough to group in one file rather than
 * one-file-per-event (same rationale as the `common` package).
 */

/** Fired once, at the end of a successful OnboardTenantUseCase run (Section 9.2 step 8). */
data class TenantOnboarded(
    val tenantId: TenantId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent

/** Fired on the DRAFT -> ACTIVE transition. */
data class TenantActivated(
    val tenantId: TenantId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent

/** Fired on any ACTIVE -> SUSPENDED transition, manual or automated. */
data class TenantSuspended(
    val tenantId: TenantId,
    val reason: String,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent

/** Fired on the SUSPENDED -> ACTIVE transition. */
data class TenantReactivated(
    val tenantId: TenantId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent

/** Fired on any -> CLOSED transition. */
data class TenantClosed(
    val tenantId: TenantId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent

/**
 * Fired specifically when the 180-day KYB verification grace period lapses
 * unverified and the scheduled sweep auto-suspends the Tenant
 * (docs/DDD_Design.md Section 9.4) - distinct from a manually-triggered
 * TenantSuspended so billing/notifications can tell the two apart.
 */
data class KybGracePeriodExpired(
    val tenantId: TenantId,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
