-- Who, why and what document, recorded with an entry (standards S4 and S6 of "Corrections and immutability of posted entries",
-- Femi 2026-10-22; GL-T-38 and GL-T-31; docs/reports/Playbooks/GL/GL_Corrections_And_Immutability_Design.md sections 2.2, 2.4, 6).
--
-- Metadata only: every column is nullable with no default, so this rewrites nothing and is safe to apply live. Nothing is backfilled:
-- an entry stored before this migration has no recorded actor, reason or source reference, and GL does not guess them.
-- (recorded_at, from V35, is the moment V35 ran for entries older than V35.)
--
-- The columns are written once, at insert, from the request's audit context; no code path updates them (S1: a posted entry is never edited).
ALTER TABLE journal_entries ADD COLUMN recorded_by VARCHAR(120);          -- 'USER:<EA user id>' or 'SERVICE:<name>'
ALTER TABLE journal_entries ADD COLUMN acting_user VARCHAR(64);           -- the EA user id a service posted on behalf of, as that service said
ALTER TABLE journal_entries ADD COLUMN reason VARCHAR(500);               -- why; mandatory on a correction, enforced by the application
ALTER TABLE journal_entries ADD COLUMN source_ref_type VARCHAR(40);       -- closed set (SourceReferenceType), validated by the application
ALTER TABLE journal_entries ADD COLUMN source_ref_id VARCHAR(100);
ALTER TABLE journal_entries ADD COLUMN source_ref_number VARCHAR(100);
ALTER TABLE journal_entries ADD COLUMN vat_basis_rate_percent VARCHAR(20);  -- exact decimal percent as sent, e.g. '13.5'
ALTER TABLE journal_entries ADD COLUMN vat_basis_rulebook_version INT;
ALTER TABLE journal_entries ADD COLUMN vat_basis_effective_from DATE;

-- For the audit read: one document can post more than one entry (a sale, then its collection), so this is not unique.
CREATE INDEX idx_journal_entries_source_ref ON journal_entries (source_ref_type, source_ref_id) WHERE source_ref_type IS NOT NULL;
