-- A posted journal entry is never edited or deleted: the DATABASE now enforces what the application already does.
--
-- GL-FR-IMM-1a to 1d / GL-T-IMM-2 (docs/GL_Posted_Entry_Immutability_Requirements.md).
-- Standard: "Corrections and immutability of posted entries" (adopted 2026-10-22), S1 to S3:
--   S1  a posted entry is never edited or deleted (no change to its lines, amounts, date, accounts or description);
--   S2  a correction is a NEW journal; the original keeps its place and is only marked REVERSED;
--   S3  the correction points to the original by a structured link, written once.
-- Authority: Decisions_Log 2026-10-23, "Posted entries are never deleted and are protected in the database" (Femi: "Once a transaction is
-- posted ... it is NEVER deleted"; "Agreed to all 3"), whose clarification says this is enforcement of S1 to S8, not new policy.
-- (S5, the reconciliation lock, is Cash and Bank Books', not the ledger's: Femi, 2026-10-23. Nothing here looks at reconciliations.)
--
-- THE RULES (row triggers, with statement triggers for TRUNCATE):
--   journal_entries  DELETE    refused.
--   journal_entries  UPDATE    refused unless every column except `status` is unchanged AND `status` is unchanged or moves POSTED -> REVERSED.
--                              Comparing the whole row as JSON (minus status) means a column added by a later migration is protected from
--                              the day it exists, and the V35/V36 audit columns (recorded_seq, recorded_at, recorded_by, acting_user, reason,
--                              source_ref_*, vat_basis_*) are write-once by the same rule. An UPDATE that leaves every column unchanged is allowed
--                              (the application's repository used to write every column on save; it now writes only `status`).
--   journal_lines    DELETE    refused.
--   journal_lines    UPDATE    refused unless the new row equals the old row (a no-op).
--   both tables      TRUNCATE  refused (TRUNCATE ... CASCADE included).
--
-- A later migration that must change these rows (for example backfilling a new column) does so deliberately with
-- ALTER TABLE ... DISABLE TRIGGER inside its own transaction and re-enables it; only the table owner can, and it shows in the migration.
--
-- APPLY PLAN (live database): metadata only, no table scan and no rewrite. CREATE FUNCTION and CREATE TRIGGER take a brief lock on the two
-- tables (writes wait for an instant). Existing rows are not checked; the triggers apply from the moment they exist. The application version that
-- writes only `status` (save() of a stored entry) MUST be live before this migration is applied: the older save() deleted and re-inserted lines
-- and would now be refused (reversals and undos would fail during the overlap of a rolling deploy).
-- ROLLBACK (if ever needed; Flyway is forward-only so this is a manual, deliberate act):
--   DROP TRIGGER journal_entries_guard ON journal_entries;  DROP TRIGGER journal_lines_guard ON journal_lines;
--   DROP TRIGGER journal_entries_no_truncate ON journal_entries;  DROP TRIGGER journal_lines_no_truncate ON journal_lines;
--   DROP FUNCTION journal_entries_guard();  DROP FUNCTION journal_lines_guard();  DROP FUNCTION journal_rows_no_truncate();

CREATE FUNCTION journal_entries_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A journal entry is never deleted (standard S1): correct a posted entry with a reversing entry'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') THEN
        RAISE EXCEPTION 'A journal entry changes only its status (standard S1): only status may change on a stored entry'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'POSTED' AND NEW.status = 'REVERSED') THEN
        RAISE EXCEPTION 'A stored journal entry moves only from POSTED to REVERSED (standard S2), not % to %', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION journal_lines_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A journal line is never deleted (standard S1): correct a posted entry with a reversing entry'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD) THEN
        RAISE EXCEPTION 'A journal line is never changed (standard S1): correct a posted entry with a reversing entry'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION journal_rows_no_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Journal entries and lines are never truncated (standard S1)'
        USING ERRCODE = 'restrict_violation';
END
$$;

CREATE TRIGGER journal_entries_guard BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION journal_entries_guard();
CREATE TRIGGER journal_lines_guard BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION journal_lines_guard();
CREATE TRIGGER journal_entries_no_truncate BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION journal_rows_no_truncate();
CREATE TRIGGER journal_lines_no_truncate BEFORE TRUNCATE ON journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION journal_rows_no_truncate();
