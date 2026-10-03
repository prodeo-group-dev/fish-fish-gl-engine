package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.audit.AuditLogEntry
import com.theprodeogroup.fish.domain.audit.AuditLogRepository
import com.theprodeogroup.fish.domain.common.AuditAction
import com.theprodeogroup.fish.domain.common.PaginatedResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.Instant

/**
 * In-memory stand-in for [AuditLogRepository] - same discipline as
 * `IdempotencyRepositoryFakes.kt`, grouped on its own here since the
 * audit trail isn't owned by any single bounded context either.
 * Mirrors `ExposedAuditLogRepository`'s own filter/pagination logic so
 * use-case-level tests exercise the real query shape, not a
 * simplified stand-in.
 */
class FakeAuditLogRepository : AuditLogRepository {
    val saveCalls = mutableListOf<AuditLogEntry>()

    override fun save(entry: AuditLogEntry) {
        saveCalls.add(entry)
    }

    override fun findByCompany(
        companyId: CompanyId,
        from: Instant?,
        to: Instant?,
        entityType: String?,
        action: AuditAction?,
        limit: Int,
        offset: Int
    ): PaginatedResult<AuditLogEntry> {
        val matching = saveCalls
            .filter { it.companyId == companyId }
            .filter { from == null || !it.occurredAt.isBefore(from) }
            .filter { to == null || !it.occurredAt.isAfter(to) }
            .filter { entityType == null || it.entityType == entityType }
            .filter { action == null || it.action == action }
            .sortedByDescending { it.occurredAt }

        val page = matching.drop(offset).take(limit)
        return PaginatedResult.of(page, matching.size.toLong(), limit, offset)
    }
}
