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

### 2.2 Architectural approach — Bank Reconciliation (open decision)

**Decision needed before Wave 2 of the backlog starts** - not picked
here, per this project's "park, don't guess" convention:

A real Bank Reconciliation feature needs, at minimum:
1. A `BankReconciliationRepository` (persisting the reconciliation's
   own identity, Account, statement date/ending balance, currency, and
   - critically - which statement-line/journal-entry pairs are already
   matched).
2. A `BankStatementLineRepository` (or the lines persisted as part of
   the reconciliation aggregate itself) - statement lines need to exist
   independently of any one HTTP request once uploaded.
3. A decision on **how a statement enters the system at all** -
   `BankStatementLine` today is a plain in-memory data class with no
   creation/upload use case. Manual one-line-at-a-time entry (mirroring
   `RecordOpeningBalanceUseCase`'s single-record shape) versus a CSV
   bulk upload (mirroring `docs/Opening_Figures_CSV_Upload_DDD_Design.md`'s
   established pattern) is a real scope choice, not an implementation
   detail - a bank statement is naturally a bulk artifact (dozens to
   hundreds of lines per period), closer to the CSV precedent than a
   single manual entry.
4. Whether `BankReconciliation.match()`'s own in-memory mutation model
   survives this change, or whether matching becomes its own persisted
   event (`BankReconciliationMatch` rows, append-only, mirroring
   `AuditLogEntry`'s own just-built shape) rather than two `Set`s
   recomputed from scratch on every load.

None of this is picked here. Recommend a dedicated, focused design
decision (a short follow-up note, not a full second SPUTO pass) before
Wave 2 of the backlog below starts - Wave 1 (Working Capital) doesn't
depend on it and can proceed immediately.

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

### FR-BANKREC-01 (Must, blocked on §2.2's decision)

A Bank Reconciliation can be created for a Cash/Bank Account, a
statement date, an ending balance, and a set of statement lines,
persisted durably.

### FR-BANKREC-02 (Must, blocked on §2.2's decision)

A statement line can be matched to a posted `JournalEntry`, with the
match persisted - a second HTTP call later in the same reconciliation's
lifecycle must see the match from the first call, not require it
resupplied.

### FR-BANKREC-03 (Must, blocked on §2.2's decision)

A read route reports a reconciliation's current state: matched/
unmatched statement lines, matched/unmatched entries, whether it's
fully reconciled (`isFullyReconciled`).

## 4. Data Requirements

**Working Capital**: none - fully derived, no new table.

**Bank Reconciliation**: genuinely open, see §2.2. At minimum a
`bank_reconciliations` table (id, company_id, account_id, statement_date,
statement_ending_balance, currency) and some persisted record of each
match (either two join tables or one `bank_reconciliation_matches`
table pairing a `statement_line_id`/`journal_entry_id`) - exact shape
depends on §2.2's statement-ingestion decision.

## 5. External Interfaces

- `GET /companies/{companyId}/reports/working-capital` (Working Capital).
- Bank Reconciliation's own routes are not named here - depend on §2.2.

## 6. Non-Functional Requirements

- **NFR-WC-01**: Same tenancy isolation discipline as every other report
  route (`authorizeTenantForRead`).
- **NFR-BANKREC-01**: Whatever persistence shape §2.2 lands on, matching
  a statement line must be safe against double-matching under concurrent
  requests (the existing `match()` method's own guard against an
  already-matched line/entry must still hold once state is persisted,
  not just in-memory for one request's lifetime).

## 7. Open Issues

§2.2's four sub-decisions block all of Bank Reconciliation's build
(Wave 2 in the backlog). Working Capital (Wave 1) is fully unblocked.
