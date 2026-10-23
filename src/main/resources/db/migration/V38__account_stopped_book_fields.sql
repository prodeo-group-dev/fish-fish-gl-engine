-- A stopped cash or bank book (GL-T-18, Femi 2026-10-23: "That cannot be stopped. It is the owners perogative"; "Final balances will be
-- reconciled as at when due ie on statement date"). When the owner stops using a book, its kind is cleared (cash_book_kind becomes NULL) but
-- nothing posted changes. These two columns let a reader (Cash and Bank Books) tell a STOPPED book from an account that was never a book, so
-- its final balance can still be reconciled on the statement date.
--   former_cash_book_kind  the kind the account had when it was last stopped (CASH or BANK); never cleared once set.
--   stopped_on             the UTC day it was last stopped; cleared when the account is designated as a book again.
-- Additive, nullable, DDL only (no row scan, no rewrite). Accounts that were books before this migration have no recorded history and keep
-- NULL in both, exactly like any account that was never a book. Rollback: ALTER TABLE accounts DROP COLUMN stopped_on, DROP COLUMN former_cash_book_kind.
ALTER TABLE accounts ADD COLUMN former_cash_book_kind VARCHAR(10);
ALTER TABLE accounts ADD COLUMN stopped_on DATE;

ALTER TABLE accounts ADD CONSTRAINT chk_accounts_former_cash_book_kind
    CHECK (former_cash_book_kind IS NULL OR (former_cash_book_kind IN ('CASH', 'BANK') AND type = 'ASSET'));
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_stopped_on
    CHECK (stopped_on IS NULL OR (former_cash_book_kind IS NOT NULL AND cash_book_kind IS NULL));
