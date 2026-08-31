-- Adds TAX to ManagedModule (2026-08-31, "Tax management should be its
-- own module"). Backfill is deliberately narrower than V15's own
-- "grant it to everyone" backfill: V15 ran when no per-module
-- restriction existed anywhere yet, so granting every existing
-- Membership every module was the only way not to silently narrow
-- access. That's no longer true - real, deliberate restrictions
-- already exist (a staff member invited with only HR access, say), and
-- blanket-granting TAX to every Membership now would undo exactly the
-- restriction the per-module system exists to enforce.
--
-- Instead: only OWNER_ADMIN-role Memberships are backfilled with TAX -
-- they already represent full trust (ADMIN access level by default),
-- so this avoids the rough edge of an existing founding admin suddenly
-- missing a tab they'd naturally expect, without silently expanding
-- any already-restricted staff Membership's access.

INSERT INTO membership_module_grants (membership_id, module)
SELECT id, 'TAX'
FROM memberships
WHERE role = 'OWNER_ADMIN'
  AND id NOT IN (
    SELECT membership_id FROM membership_module_grants WHERE module = 'TAX'
  );
