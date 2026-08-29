-- AccessLevel becomes a genuinely independent field on memberships, not
-- derived from role at query time (docs/DDD_Design.md-adjacent,
-- 2026-08-29) - see Membership.kt's own KDoc and AccessLevel's KDoc for
-- the full "Role vs AccessLevel" reasoning. Existing rows are backfilled
-- using the exact same role -> default AccessLevel mapping
-- Membership.defaultAccessLevelFor() applies to a fresh grant() call, so
-- a pre-existing Membership's effective access is unchanged by this
-- migration - it just becomes an explicit column instead of an implicit
-- rule.

ALTER TABLE memberships
    ADD COLUMN access_level VARCHAR(10);

UPDATE memberships SET access_level = CASE role
    WHEN 'OWNER_ADMIN' THEN 'ADMIN'
    WHEN 'ACCOUNTANT' THEN 'WRITE'
    WHEN 'APPROVER' THEN 'APPROVE'
    WHEN 'READ_ONLY' THEN 'READ'
    WHEN 'COMPLIANCE_ETHICS_REVIEW' THEN 'READ'
END;

ALTER TABLE memberships ALTER COLUMN access_level SET NOT NULL;
