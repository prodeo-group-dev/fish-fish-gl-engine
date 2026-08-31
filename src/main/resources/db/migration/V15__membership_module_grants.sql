-- Which of GL/HR/SOP/POP/IM a Membership can actually open (2026-08-31,
-- "permissions may be given to some staff/employees to run the FiSH
-- modules") - binary for now, not a role-per-module (see Membership.kt's
-- own KDoc). A one-to-many child table, same shape as
-- V11's company_module_management_preferences, chosen specifically
-- because it leaves room to add a per-module access_level column later
-- without restructuring - the user's own "let's KIV [role-per-module]"
-- direction.

CREATE TABLE membership_module_grants (
    membership_id UUID NOT NULL REFERENCES memberships(id),
    module VARCHAR(10) NOT NULL,
    PRIMARY KEY (membership_id, module)
);

-- Backfill: every existing Membership gets every module, matching
-- Membership.grant()'s own new default (ManagedModule.entries.toSet())
-- - no existing caller's access silently narrows because this migration ran.
INSERT INTO membership_module_grants (membership_id, module)
SELECT id, module_name
FROM memberships, unnest(ARRAY['GL', 'HR', 'SOP', 'POP', 'IM']) AS module_name;
