package com.theprodeogroup.fish.domain.audit

import java.util.UUID

/**
 * Identity of an [AuditLogEntry] (docs/GL_Audit_Trail_Software_Requirements_Specification.md).
 */
@JvmInline
value class AuditLogEntryId(val value: UUID) {
    companion object {
        fun generate(): AuditLogEntryId = AuditLogEntryId(UUID.randomUUID())
    }
}
