-- BankReconciliation (docs/GL_Working_Capital_And_Bank_Reconciliation_Software_Requirements_Specification.md
-- Section 2.2/4, decided 2026-10-03) - bulk synchronous statement
-- ingestion, deletable persisted match pairs (not append-only events,
-- unlike audit_log_entries - a reconciliation match has no compliance
-- reason to stay immutable).

CREATE TABLE bank_reconciliations (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    account_id UUID NOT NULL,
    statement_date DATE NOT NULL,
    statement_ending_balance_amount DECIMAL(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL
);

CREATE INDEX idx_bank_reconciliations_company_id ON bank_reconciliations(company_id);

-- Statement lines are an embedded child of their reconciliation (no
-- independent lifecycle, per the decided shape) - inserted once at
-- Start time, never updated/deleted afterward.
CREATE TABLE bank_statement_lines (
    id UUID PRIMARY KEY,
    reconciliation_id UUID NOT NULL REFERENCES bank_reconciliations(id),
    line_date DATE NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    description VARCHAR(500) NOT NULL
);

CREATE INDEX idx_bank_statement_lines_reconciliation_id ON bank_statement_lines(reconciliation_id);

-- Plain insert/delete pairs table, not append-only (FR-BANKREC-04's
-- decided shape - unmatch deletes the row outright). statement_line_id
-- as the PRIMARY KEY plus a UNIQUE constraint on journal_entry_id both
-- enforce, at the database level, the same "each side matches at most
-- once" invariant BankReconciliation.match() already checks in memory -
-- closing the real concurrent-request race a single process's own
-- in-memory check can't (NFR-BANKREC-01).
CREATE TABLE bank_reconciliation_matches (
    statement_line_id UUID PRIMARY KEY REFERENCES bank_statement_lines(id),
    journal_entry_id UUID NOT NULL UNIQUE REFERENCES journal_entries(id)
);
