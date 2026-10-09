-- A bank reconciliation could be started, matched and unmatched but never finished or abandoned
-- (UAT v2.2 W-M2). It now has a status: OPEN (accepts matches), COMPLETED or CANCELLED (final).
-- Every existing reconciliation is OPEN. Additive and safe to run on a live database.
ALTER TABLE bank_reconciliations ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'OPEN';
