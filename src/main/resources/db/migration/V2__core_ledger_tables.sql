-- Core Ledger tables (docs/DDD_Design.md Section 10.1/3.1) - Account,
-- Period, JournalEntry/JournalLine. Money isn't its own table - it's
-- embedded as (amount, currency) columns on journal_lines, the only
-- place a bare Money value is actually stored.
--
-- company_id columns deliberately have NO foreign key constraint - the
-- Tenancy context's Company aggregate isn't persisted yet. The column
-- still exists (it's a real domain relationship, Account.companyId
-- etc.), just unconstrained until Company gets its own table.

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    classification VARCHAR(20),
    expense_classification VARCHAR(30),
    code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    parent_id UUID REFERENCES accounts(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    has_posted_activity BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_accounts_company_id ON accounts(company_id);

CREATE TABLE periods (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    period_type VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE INDEX idx_periods_company_id ON periods(company_id);

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    period_id UUID NOT NULL REFERENCES periods(id),
    entry_date DATE NOT NULL,
    source VARCHAR(20) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL,
    reversal_of_entry_id UUID REFERENCES journal_entries(id)
);

CREATE INDEX idx_journal_entries_period_id ON journal_entries(period_id);

-- One row per JournalLine. JournalLine has no identity of its own in
-- the domain model (a plain data class, part of the JournalEntry
-- aggregate) - (journal_entry_id, line_index) is a synthetic key that
-- preserves line order without changing the domain type. dimensions
-- stores the DimensionType -> String map as a small hand-encoded JSON
-- string in a plain TEXT column, not genuine `jsonb` - every dimension
-- value in the codebase today is a UUID or enum name (never queried
-- directly by dimension value), so this avoids a JSON serialization
-- library dependency and jsonb driver-type-mapping complexity for a
-- narrow, well-understood case. Revisit if dimensions ever needs to be
-- queried/filtered at the SQL level.
CREATE TABLE journal_lines (
    journal_entry_id UUID NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
    line_index INT NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    side VARCHAR(10) NOT NULL,
    dimensions TEXT NOT NULL DEFAULT '{}',
    PRIMARY KEY (journal_entry_id, line_index)
);

CREATE INDEX idx_journal_lines_account_id ON journal_lines(account_id);
