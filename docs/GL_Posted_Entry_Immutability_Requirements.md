# GL Posted-Entry Immutability: requirement, use cases and tasks (GL-FR-IMM-1)

**Owner:** the GL session. **Status:** requirement and tasks written 2026-10-23 for the work CM assigned and Femi agreed ("Agreed to all 3") the same day. This is GL's Playbook row for it: no Playbook, no code. Nothing here is new policy.

## Authority (what this traces to)

1. **The adopted standard "Corrections and immutability of posted entries", S1 to S8 (Femi, 2026-10-22)**, recorded in `docs/Decisions_Log.md` (FiSH repo) under 2026-10-22. This work is the **enforcement of S1, S2 and S3** inside GL's own database and repository. S4 (who, when, why), S6 (source reference) and S7 are other tasks; S5 (the reconciliation lock) belongs to Cash and Bank Books, not the ledger (Femi, 2026-10-23).
2. **Decisions_Log 2026-10-23, "Posted entries are never deleted and are protected in the database"** (Femi: "Once a transaction is posted ... it is NEVER deleted."; to CM's three proposals "Agreed to all 3"), and its **clarification of the same day**: the rule is not new; it is S1 to S8; today's decision adds only the *enforcement* in GL's database and the saving change (and a transaction number, which is a separate, still-unbuilt design: `docs/GL_Transaction_Number_Design.md` in the FiSH repo).

**Not cited as authority:** the audit-trail Playbook. Femi's 2026-10-22 approval of it covered only T-AUD-CBB1 (Decisions_Log 2026-10-23, "audit-trail approval is limited to T-AUD-CBB1"). The transaction number stays at design-note stage and is not part of this requirement.

## Requirement

**GL-FR-IMM-1. A posted entry's rows are physically immutable.** Once a journal entry is stored, the database and the repository together guarantee:

| ID | Rule | Standard |
|---|---|---|
| GL-FR-IMM-1a | No journal line is ever deleted or changed, and no journal entry is ever deleted. | S1 |
| GL-FR-IMM-1b | A stored entry changes in exactly one way: its `status` moves POSTED to REVERSED, and nothing else changes in the same statement. Back, sideways, or to DRAFT/PENDING is refused. | S1, S2 |
| GL-FR-IMM-1c | Every other column of an entry (period, date, source, description, the reversal link, the V35/V36 audit and provenance columns, the VAT basis) is written once, at insert, and never changed. A statement that changes nothing is allowed. | S1, S3 |
| GL-FR-IMM-1d | The journal tables cannot be truncated. | S1 |
| GL-FR-IMM-1e | Saving an entry that is already stored writes at most its status; it never deletes and re-inserts its lines, and it refuses (a programming error, never a user path) a save that carries any other difference. | S1, S2 |

A correction is a NEW journal that reverses the original (S2); this requirement does not change how corrections are made, only makes it impossible to correct any other way.

## Use cases

- **UC-IMM-1.** A user undoes or reverses a posted entry: a reversal is stored as a new entry linked to the original, the original is marked REVERSED, its lines are untouched (S2, S3).
- **UC-IMM-2.** Someone with database access (a script, a tool, a mistaken manual statement) tries to delete or edit a posted entry or line, or truncate the tables: the database refuses with a plain message naming the rule (S1).
- **UC-IMM-3.** An application saves an entry that is already stored: nothing is rewritten; an attempt to save different content under a stored id is refused (S1).

## Tasks, in order

| Task | What | Branch | Depends on |
|---|---|---|---|
| **GL-T-IMM-1** | `ExposedJournalEntryRepository.save()` on a stored entry writes only `status` and refuses any other difference (GL-FR-IMM-1e). | `gl/save-keeps-posted-lines` | none |
| **GL-T-IMM-2** | Migration **V37**: BEFORE UPDATE/DELETE row triggers on `journal_entries` and `journal_lines`, BEFORE TRUNCATE statement triggers on both (GL-FR-IMM-1a to 1d). | `gl/immutable-posted-rows-triggers`, stacked on GL-T-IMM-1 | **GL-T-IMM-1 must be live first**: the older `save()` deleted and re-inserted lines and would be refused by V37 during a rolling deploy. |

Tests are written first on a real Postgres (`integrationTest`): `JournalEntryRepositoryPostedRowsIntegrationTest` (GL-T-IMM-1) and `PostedJournalRowsAreImmutableIntegrationTest` (GL-T-IMM-2), each with mutation checks. Test cleanup never deletes or truncates the journal tables.

## Apply plan for V37 (live database)

DDL only: three functions and four triggers. No table scan, no rewrite, no data change; `CREATE TRIGGER` takes a brief lock on each table (writes wait an instant). Existing rows are not checked. Rollback is a manual `DROP TRIGGER` / `DROP FUNCTION` (listed in the migration's comments). Anything outside GL that deletes or updates journal rows (scripts, tooling, the application database role's DELETE/TRUNCATE grants) is CM's to find and remove; a later migration that must change these rows disables the trigger deliberately, as the table owner, inside its own transaction.
