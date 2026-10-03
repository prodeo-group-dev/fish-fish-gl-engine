package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table definition for [com.theprodeogroup.fish.domain.audit.AuditLogEntry]
 * (`V27__audit_log_entries.sql`, docs/GL_Audit_Trail_Software_Requirements_Specification.md
 * Section 4). Append-only - no code anywhere issues an `update`/`delete`
 * against this table, matching [AuditLogRepository]'s own omission of
 * those operations.
 */
object AuditLogEntriesTable : Table("audit_log_entries") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val companyId = uuid("company_id")
    val actorEmail = varchar("actor_email", 255)
    val action = varchar("action", 30)
    val entityType = varchar("entity_type", 100)
    val entityId = varchar("entity_id", 100)
    val detail = text("detail").nullable()
    val occurredAt = timestamp("occurred_at")

    override val primaryKey = PrimaryKey(id)
}
