-- AuditLogEntry (docs/GL_Audit_Trail_Software_Requirements_Specification.md)
-- - append-only by design, no update/delete path exists on this table.
--
-- tenant_id stored directly (no FK) - same precedent as companies.tenant_id
-- (see V21__drop_companies_tenant_fk.sql's own comment): GL's local
-- tenants table is dead/unreferenced since Tenant moved to EA, so a
-- cross-database FK was never really enforceable here either.
CREATE TABLE audit_log_entries (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    company_id UUID NOT NULL REFERENCES companies(id),
    actor_email VARCHAR(255) NOT NULL,
    action VARCHAR(30) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    detail TEXT,
    occurred_at TIMESTAMP NOT NULL
);

-- Primary read access pattern (FR-AUDIT-02/NFR-AUDIT-02): list a
-- Company's entries newest-first, optionally filtered by date range.
CREATE INDEX idx_audit_log_entries_company_occurred ON audit_log_entries(company_id, occurred_at DESC);
