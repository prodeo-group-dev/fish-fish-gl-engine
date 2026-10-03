# Working Capital & Bank Reconciliation — Software Requirements Specification

**Status**: SPUTO pass, 2026-10-03, per direct instruction relayed via
WEB, which found both wrongly marked "backend complete" in
`docs/WEB_Frontend_Backlog.md` while scoping its own Bank Reconciliation
screen (WEB.2/WEB.6). Confirmed directly, not assumed: `ReportsRoutes.kt`
has exactly three routes (`balance-sheet`/`profit-and-loss`/`cash-flow`);
neither feature has an application use case, repository, or HTTP route.

## 1. Introduction

### 1.1 Purpose

Two reports GL's own domain layer already models correctly
(`WorkingCapital`, `BankReconciliation`) but never exposed past a
domain class + domain test. This SRS scopes closing that gap for both -
**but they are not the same size of problem**, confirmed by reading
both classes directly before writing a single requirement below.

### 1.2 Scope

**Working Capital** (`domain/ledger/working_capital.kt`): a pure,
stateless, point-in-time report - `WorkingCapital.of(accounts,
postedEntries, currency)` derives everything fresh from already-posted
`JournalEntry` data, identical in shape to `BalanceSheet`/`TrialBalance`,
which `ReportsRoutes.kt` already exposes. **No new persistence is
needed** - this is the same "just add a use case + route" gap the AR/AP
aging work (`docs/GL_POP_IM_SOP_Backlog.md`, 2026-10-02) already closed
twice for a structurally identical situation.

**Bank Reconciliation** (`domain/ledger/bank_reconciliation.kt`): a
genuinely different, bigger problem. `BankReconciliation.match()`
mutates two in-memory `Set`s (`matchedStatementLineIds`/
`matchedJournalEntryIds`) that exist only for the lifetime of the object
- **confirmed directly: no `BankReconciliationRepository` or
`BankStatementLineRepository` exists anywhere in this codebase, and
`BankReconciliation` itself has no `reconstitute()`.** A real
reconciliation workflow is inherently multi-step (upload a statement,
then match lines one at a time, possibly across multiple sessions) -
without persistence, every HTTP call would need the caller to resupply
the entire reconciliation's state (every statement line, every already-
matched pair) on every single request, which is not a workable API.
**This SRS does not treat the two features as equally scoped** - Bank
Reconciliation needs a real persistence design decision (§2.2) before
any route gets built; Working Capital does not.

**Out of scope**: WEB's own screen work (routed through WEB per
`feedback_frontend_routes_through_web` once routes/DTOs exist).
Reconciliation matching heuristics (fuzzy amount/date matching) -
`BankReconciliation`'s own KDoc already confirms this was a deliberate
2026-08-12 scope decision ("manual/explicit matching only... no amount/
date heuristic is invented") and this SRS doesn't reopen it. Balance-
adjustment tie-out math (deposits in transit, unpresented cheques) -
same prior scope decision, not reopened here.

### 1.3 Definitions

- **Statement line**: one line of an externally-sourced bank statement
  (`BankStatementLine` - amount, date, direction).
- **Match**: pairing one statement line with one posted `JournalEntry`
  against the same Account, same amount, same implied side.

## 2. Overall Description

### 2.1 Architectural approach — Working Capital

Mirror `ComputeBalanceSheetUseCase`/`ReportsRoutes.kt` exactly: a new
`ComputeWorkingCapitalUseCase(companyRepository, accountRepository,
journalEntryRepository)` wrapping `WorkingCapital.of()`, exposed as
`GET /companies/{companyId}/reports/working-capital`, added to the
existing `reportsRoutes()` function alongside its three siblings rather
than a new route file - it's the same report family.

### 2.2 Architectural approach — Bank Reconciliation (decided 2026-10-03)

**Both sub-decisions resolved by Femi, relayed via WEB**, points 1/2
(repository + statement-line persistence) following automatically from
them rather than being separate decisions:

**Statement ingestion (was point 3) - a lightweight bulk endpoint, not
the full `OpeningImportBatch` machinery.** One request carries the
whole statement's lines, created synchronously in one call - no
batch-status tracking (`DRAFT`/`VALIDATING`/`VALIDATED`/`COMMITTING`/
`COMMITTED`), no async path. Reasoning: a bank statement is naturally
bulk, but the Opening Figures CSV pattern was built for a one-time,
cross-domain, >2000-row-capable import - a monthly bank statement is
smaller and single-purpose, so that full state machine is more than
this needs. Manual one-line-at-a-time entry was also ruled out as too
painful for real statement volume.

**Match persistence (was point 4) - a simple persisted pairs table, not
append-only events.** Statement-line/journal-entry matches are stored
directly and deletable (unmatch = delete the row), not an
`AuditLogEntry`-style immutable event log. Reasoning: `AuditLogEntry`'s
immutability exists because compliance requires the record itself never
be edited - a reconciliation match has no such requirement, and a
fat-fingered match should be correctable by deleting the pairing, not by
appending a correcting event. Event-sourcing this would solve a problem
Bank Reconciliation doesn't have.

**Resulting concrete shape**:
- `BankReconciliationRepository` persists the reconciliation's own
  identity/Account/statement date/ending balance/currency, plus its
  statement lines (embedded, not a separate repository - they have no
  independent lifecycle outside their parent reconciliation).
- A `bank_reconciliation_matches` table pairs `statement_line_id`/
  `journal_entry_id`, plain insert/delete - no append-only discipline.
- `BankReconciliation` gains `internal reconstitute()` (loading
  already-matched pairs back into its existing in-memory `Set`s, same
  "pure in-memory until explicitly persisted" shape every other
  aggregate in this codebase uses) and a new `unmatch()` method
  (`match()` already existed; there was no way to undo one before this
  decision).
- `StartBankReconciliationUseCase` creates a reconciliation and all its
  statement lines in one synchronous call, per the ingestion decision.
- `MatchBankReconciliationLineUseCase`/`UnmatchBankReconciliationLineUseCase`
  wrap `BankReconciliation.match()`/`unmatch()` against the now-persisted
  state.

## 3. System Features

### FR-WC-01 (Must)

`ComputeWorkingCapitalUseCase` computes `WorkingCapital.of()` for a
Company's current Accounts and already-posted `JournalEntry` data,
reusing exactly the existing domain logic - no new business rule.

### FR-WC-02 (Must)

`GET /companies/{companyId}/reports/working-capital` - read-only,
`authorizeTenantForRead` (same access level as every other report route
in `ReportsRoutes.kt`, not the stricter Owner-Admin level the Audit
Trail's own route uses - Working Capital isn't sensitive the way an
audit log is).

### FR-BANKREC-01 (Must)

A Bank Reconciliation can be created for a Cash/Bank Account, a
statement date, an ending balance, and a set of statement lines,
persisted durably.

### FR-BANKREC-02 (Must)

A statement line can be matched to a posted `JournalEntry`, with the
match persisted - a second HTTP call later in the same reconciliation's
lifecycle must see the match from the first call, not require it
resupplied.

### FR-BANKREC-03 (Must)

A read route reports a reconciliation's current state: matched/
unmatched statement lines, matched/unmatched entries, whether it's
fully reconciled (`isFullyReconciled`).

### FR-BANKREC-04 (Must)

A match can be undone (unmatch) - deletes the persisted pair outright,
per §2.2's decision, not a correcting event appended alongside it.

## 4. Data Requirements

**Working Capital**: none - fully derived, no new table.

**Bank Reconciliation** (decided, §2.2): `bank_reconciliations` (id,
company_id, account_id, statement_date, statement_ending_balance,
currency), `bank_statement_lines` (id, reconciliation_id, amount, date,
direction - embedded child of a reconciliation, no independent
lifecycle), `bank_reconciliation_matches` (statement_line_id,
journal_entry_id, plain insert/delete, no append-only discipline).

## 5. External Interfaces

- `GET /companies/{companyId}/reports/working-capital` (Working Capital).
- Bank Reconciliation's own routes are not named here - depend on §2.2.

## 6. Non-Functional Requirements

- **NFR-WC-01**: Same tenancy isolation discipline as every other report
  route (`authorizeTenantForRead`).
- **NFR-BANKREC-01**: Matching a statement line must be safe against
  double-matching under concurrent requests - the existing `match()`
  method's own guard against an already-matched line/entry must still
  hold once state is persisted via `bank_reconciliation_matches`, not
  just in-memory for one request's lifetime (a unique constraint on
  `statement_line_id` in that table, not just the application-level
  check, closes the real concurrent-request race `match()` alone can't).

## 7. Open Issues

§2.2 is now decided (2026-10-03) - nothing blocks Wave 2 of the backlog
anymore. Working Capital (Wave 1) was already fully unblocked.
