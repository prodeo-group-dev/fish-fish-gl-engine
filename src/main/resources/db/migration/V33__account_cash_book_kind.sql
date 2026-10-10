-- Cash and bank accounts (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB01, Femi 2026-10-10):
-- an ASSET account may be flagged CASH or BANK, which gives it a book (and, for BANK,
-- bank reconciliation). The account stays an ordinary GL account; this column only says
-- what it can do. Additive and nullable: accounts keep NULL unless backfilled below.
ALTER TABLE accounts ADD COLUMN cash_book_kind VARCHAR(10);

ALTER TABLE accounts ADD CONSTRAINT chk_accounts_cash_book_kind
    CHECK (cash_book_kind IS NULL OR (cash_book_kind IN ('CASH', 'BANK') AND type = 'ASSET'));

-- Account 1000 is the Cash Book in every Company, in every country (SRS decision D4, Femi
-- 2026-10-10). Bank accounts are further cash books the owner creates; none is backfilled.
UPDATE accounts SET cash_book_kind = 'CASH' WHERE code = '1000' AND type = 'ASSET';
