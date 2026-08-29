-- "Who manages this - you, or someone else?" per ManagedModule (GL/HR/
-- SOP/POP/IM), captured during Company setup (docs/DDD_Design.md-adjacent,
-- 2026-08-29). Intent capture only, not access control - see
-- ModuleManagementPreference's own KDoc for what that does and doesn't
-- mean. A one-to-many child table, not columns on companies, since a
-- Company has zero to five of these (most existing Companies have zero -
-- this table simply has no rows for them, which is the correct
-- "not specified" state, not a migration backfill concern).

CREATE TABLE company_module_management_preferences (
    company_id UUID NOT NULL REFERENCES companies(id),
    module VARCHAR(10) NOT NULL,
    self_managed BOOLEAN NOT NULL,
    delegate_name VARCHAR(255),
    delegate_email VARCHAR(255),
    PRIMARY KEY (company_id, module)
);
