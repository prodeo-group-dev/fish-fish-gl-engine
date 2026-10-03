package com.theprodeogroup.fish.domain.audit

import com.theprodeogroup.fish.domain.common.AuditAction
import com.theprodeogroup.fish.domain.common.PaginatedResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.Instant

/**
 * Persistence contract for [AuditLogEntry]
 * (docs/GL_Audit_Trail_Software_Requirements_Specification.md Section 4).
 * **No `update`/`delete` method exists at all** - FR-AUDIT-04's
 * append-only guarantee is enforced by omission from this interface,
 * the same precedent `MembershipRepository` and others already
 * establish for "this kind of record is never supposed to change."
 */
interface AuditLogRepository {
    fun save(entry: AuditLogEntry)

    fun findByCompany(
        companyId: CompanyId,
        from: Instant? = null,
        to: Instant? = null,
        entityType: String? = null,
        action: AuditAction? = null,
        limit: Int = 50,
        offset: Int = 0
    ): PaginatedResult<AuditLogEntry>
}
