-- Same-day order in the books (CM item 7, 2026-10-11).
--
-- Two entries on the same date had no stored order: the cash book sorted by date only, and the repository read
-- them in whatever order the database returned, so a day's running balance could change between two reads.
--
-- recorded_seq is an identity column: the database numbers each entry as it is inserted, so the order entries were
-- recorded in is kept, and the application never writes it (it cannot be set or changed by a request).
-- recorded_at is the matching timestamp, for people and audits only; ordering uses recorded_seq, because two
-- entries can share a timestamp and a sequence cannot.
--
-- BACKFILL RULE: entries that already exist are numbered by this ALTER in the order Postgres stores the rows
-- (normally the order they were inserted), and their recorded_at is the moment of this migration. The true order of
-- two same-day entries recorded before this migration is not known and is not guessed; from now on it is fixed
-- (the numbers are stable) and every read, in every service, gives the same order.
ALTER TABLE journal_entries ADD COLUMN recorded_seq BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE journal_entries ADD COLUMN recorded_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_journal_entries_date_recorded_seq ON journal_entries (entry_date, recorded_seq);
