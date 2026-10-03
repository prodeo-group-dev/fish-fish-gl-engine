package com.theprodeogroup.fish.domain.audit

import com.theprodeogroup.fish.domain.common.AuditAction
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import java.time.Instant

/**
 * One immutable record of "actor X did action Y to entity Z at time T,
 * for Company C" (docs/GL_Audit_Trail_Software_Requirements_Specification.md
 * Section 1.3/4). **Append-only by construction (FR-AUDIT-04)** - no
 * mutator exists on this class at all, unlike every other aggregate in
 * this codebase that tracks a lifecycle (`JournalEntry.reverse()`,
 * `Period.close()`, etc.). Correcting a wrong entry is not possible by
 * design; a wrong entry is simply a permanent part of the record, same
 * as a wrongly-posted `JournalEntry` is corrected by a reversal, never
 * an edit.
 *
 * [actorEmail], not a `userId` - GL has no local User identity since
 * the Tenancy/Administration extraction moved `User`/`Membership` to EA
 * (confirmed directly: every write route resolves an
 * `AuthorizedCaller(email, name)`, not a UUID). The original
 * `RequestContext` scaffolding (`domain.common`) assumed a `userId:
 * UUID` and predates that extraction - deliberately not reused here.
 *
 * [entityType]/[entityId] are a loose (string, string) pair, not a
 * typed reference - this class has to describe actions against every
 * kind of entity in the system (`JournalEntry`, `Account`, `Period`,
 * `Company`, etc.), and a typed union across all of them would be a
 * large, constantly-growing sealed hierarchy for marginal benefit over
 * a short string pair a reader can still filter/search on.
 */
class AuditLogEntry private constructor(
    val id: AuditLogEntryId,
    val tenantId: TenantId,
    val companyId: CompanyId,
    val actorEmail: String,
    val action: AuditAction,
    val entityType: String,
    val entityId: String,
    val detail: String?,
    val occurredAt: Instant
) {
    companion object {
        /** Well-known actor attributed to a system-generated posting with no authenticated human caller (FR-AUDIT-06). */
        const val SYSTEM_ACTOR_EMAIL = "system@theprodeogroup.com"

        fun create(
            tenantId: TenantId,
            companyId: CompanyId,
            actorEmail: String,
            action: AuditAction,
            entityType: String,
            entityId: String,
            detail: String? = null,
            occurredAt: Instant = Instant.now(),
            id: AuditLogEntryId = AuditLogEntryId.generate()
        ): AuditLogEntry {
            require(actorEmail.isNotBlank()) { "actorEmail must not be blank" }
            require(entityType.isNotBlank()) { "entityType must not be blank" }
            require(entityId.isNotBlank()) { "entityId must not be blank" }
            return AuditLogEntry(id, tenantId, companyId, actorEmail, action, entityType, entityId, detail, occurredAt)
        }

        /** Rebuilds an already-valid entry from persisted state - `internal`, matching every other aggregate's `reconstitute()` visibility. */
        internal fun reconstitute(
            id: AuditLogEntryId,
            tenantId: TenantId,
            companyId: CompanyId,
            actorEmail: String,
            action: AuditAction,
            entityType: String,
            entityId: String,
            detail: String?,
            occurredAt: Instant
        ): AuditLogEntry = AuditLogEntry(id, tenantId, companyId, actorEmail, action, entityType, entityId, detail, occurredAt)
    }
}
