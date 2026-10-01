-- Persists OpeningImportBatch/OpeningImportRowResult
-- (docs/Opening_Figures_CSV_Upload_DDD_Design.md Section 3) - the GL
-- balances importer (ImportGlBalancesUseCase) built the application/
-- domain layer against Fake repositories first; this is their first
-- real storage. Same subsidiary-ledger shape as V20__fixed_assets.sql:
-- an audit/tracking record alongside the journal_entries these imports
-- post, not itself part of the posted ledger data.

CREATE TABLE opening_import_batches (
    id UUID PRIMARY KEY,
    domain VARCHAR(20) NOT NULL,
    company_id UUID NOT NULL REFERENCES companies(id),
    anchor_date DATE NOT NULL,
    filename_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    row_count INT NOT NULL,
    accepted_count INT NOT NULL,
    rejected_count INT NOT NULL,
    needs_itemization_count INT NOT NULL,
    created_by_email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    committed_at TIMESTAMP
);

CREATE INDEX idx_opening_import_batches_company_id ON opening_import_batches(company_id);

-- errors/resulting_entity_ids are short diagnostic lists (FR-UP6), not
-- queryable relational data - stored as a single TEXT column joined on
-- ASCII Unit Separator (U+001F), the same "simple delimited text over a
-- join table for a short, display-only list" shape
-- rate_structure_encoding.kt already established, with a separator
-- that won't collide with real error-message punctuation (unlike a
-- comma).
CREATE TABLE opening_import_row_results (
    batch_id UUID NOT NULL REFERENCES opening_import_batches(id),
    row_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    errors TEXT NOT NULL,
    resulting_entity_ids TEXT NOT NULL,
    PRIMARY KEY (batch_id, row_number)
);
