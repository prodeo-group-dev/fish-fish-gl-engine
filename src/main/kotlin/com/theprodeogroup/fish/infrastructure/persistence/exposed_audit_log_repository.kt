package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.audit.AuditLogEntry
import com.theprodeogroup.fish.domain.audit.AuditLogEntryId
import com.theprodeogroup.fish.domain.audit.AuditLogRepository
import com.theprodeogroup.fish.domain.common.AuditAction
import com.theprodeogroup.fish.domain.common.PaginatedResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/** Exposed-backed [AuditLogRepository]. `save()` is the only write - no `update`/`delete` exists. */
class ExposedAuditLogRepository : AuditLogRepository {

    override fun save(entry: AuditLogEntry): Unit = transaction {
        AuditLogEntriesTable.insert { statement ->
            statement[id] = entry.id.value
            statement[tenantId] = entry.tenantId.value
            statement[companyId] = entry.companyId.value
            statement[actorEmail] = entry.actorEmail
            statement[action] = entry.action.name
            statement[entityType] = entry.entityType
            statement[entityId] = entry.entityId
            statement[detail] = entry.detail
            statement[occurredAt] = entry.occurredAt
        }
        Unit
    }

    override fun findByCompany(
        companyId: CompanyId,
        from: Instant?,
        to: Instant?,
        entityType: String?,
        action: AuditAction?,
        limit: Int,
        offset: Int
    ): PaginatedResult<AuditLogEntry> = transaction {
        var condition = AuditLogEntriesTable.companyId eq companyId.value
        from?.let { condition = condition and (AuditLogEntriesTable.occurredAt greaterEq it) }
        to?.let { condition = condition and (AuditLogEntriesTable.occurredAt lessEq it) }
        entityType?.let { condition = condition and (AuditLogEntriesTable.entityType eq it) }
        action?.let { condition = condition and (AuditLogEntriesTable.action eq it.name) }

        val total = AuditLogEntriesTable.selectAll().where { condition }.count()
        val items = AuditLogEntriesTable.selectAll().where { condition }
            .orderBy(AuditLogEntriesTable.occurredAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toAuditLogEntry() }

        PaginatedResult.of(items, total, limit, offset)
    }

    private fun ResultRow.toAuditLogEntry(): AuditLogEntry = AuditLogEntry.reconstitute(
        id = AuditLogEntryId(this[AuditLogEntriesTable.id]),
        tenantId = TenantId(this[AuditLogEntriesTable.tenantId]),
        companyId = CompanyId(this[AuditLogEntriesTable.companyId]),
        actorEmail = this[AuditLogEntriesTable.actorEmail],
        action = AuditAction.valueOf(this[AuditLogEntriesTable.action]),
        entityType = this[AuditLogEntriesTable.entityType],
        entityId = this[AuditLogEntriesTable.entityId],
        detail = this[AuditLogEntriesTable.detail],
        occurredAt = this[AuditLogEntriesTable.occurredAt]
    )
}
