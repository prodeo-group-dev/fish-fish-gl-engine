# Working Capital & Bank Reconciliation — Dependency-Ordered Backlog

Companion to `GL_Working_Capital_And_Bank_Reconciliation_Software_Requirements_Specification.md` /
`..._Use_Cases.md`. Working Capital (Wave 1) is live in production.
Bank Reconciliation (Wave 2)'s design decision (SRS §2.2) is resolved -
bulk synchronous statement ingestion, deletable persisted match pairs -
nothing blocks starting it now.

**Coordination**: GL-internal, single-service work for Wave 1. Wave 2
is also GL-internal (no cross-service wire change), but is large enough
in scope that a coordination row is worth claiming when it actually
starts, same judgment call as the Audit Trail backlog's own note.

| # | Item | Depends on | Status |
|---|---|---|---|
| 1.1 | ~~`ComputeWorkingCapitalUseCase`~~ | — | **Done** (GL `58642e0`, local, handed to CM). |
| 1.2 | ~~`GET /companies/{companyId}/reports/working-capital`~~ | 1.1 | **Done** - added to `reportsRoutes()` alongside its three siblings, `WorkingCapitalResponseDto` in `Dtos.kt`. |
| 1.3 | ~~Wire into `Application.kt`~~ | 1.2 | **Done** - `computeWorkingCapitalUseCase` is a defaulted `fishModule` param (constructed from repos already in scope), not a new required one, so none of the ~25 existing test fixtures needed touching. 3 new tests in `ReportsRoutesTest.kt`, full suite green. |
| 2.0 | ~~**Decision gate**~~ | — | **Resolved 2026-10-03** - bulk synchronous statement ingestion, deletable persisted match pairs (SRS §2.2). |
| 2.1 | ~~`BankReconciliation.unmatch()` + `internal reconstitute()`~~ | 2.0 | **Done** (GL `e6631bf`). Found and fixed a real gap while scoping this: the existing model tracked two *independent* matched-id Sets, not the actual pairing - couldn't know which entry a line was matched to, which `unmatch()` needs. Replaced with one `Set<Pair<...>>`, same public behavior otherwise. |
| 2.2 | ~~Migration + Exposed tables + repositories~~ | 2.1 | **Done** - `V28__bank_reconciliation_tables.sql`, `ExposedBankReconciliationRepository`, `FakeBankReconciliationRepository`. `statement_line_id` PRIMARY KEY + `journal_entry_id` UNIQUE enforce NFR-BANKREC-01 at the DB level. |
| 2.3 | ~~`StartBankReconciliationUseCase`~~ | 2.2 | **Done**. |
| 2.4 | ~~`MatchBankReconciliationLineUseCase`/`UnmatchBankReconciliationLineUseCase`~~ | 2.2 | **Done**. |
| 2.5 | ~~`ComputeBankReconciliationUseCase` + `GET` route~~ | 2.2 | **Done**. |
| 2.6 | ~~Routes wired into `Application.kt`~~ | 2.3, 2.4, 2.5 | **Done** - four nullable `fishModule` params (no "free" default, unlike Working Capital's), conditionally registered. 4 new `BankReconciliationTest` cases + 9 `BankReconciliationRoutesTest` cases + 4 `BankReconciliationRepositoryIntegrationTest` cases (written, compiles, not run live - same local-DB-credentials gap as Audit Trail Wave 1, flagged for CM). Full unit suite green. |

## Change log

- **2026-10-03 (GL session)**: Initial SPUTO pass (SRS + Use Cases +
  this backlog), per direct instruction relayed via WEB (which found
  both features wrongly marked "backend complete" in
  `docs/WEB_Frontend_Backlog.md` while scoping its own Bank
  Reconciliation screen). Grounded directly against code: confirmed
  `WorkingCapital.of()` is a pure stateless derivation (same shape as
  `BalanceSheet`, trivial to expose) but `BankReconciliation` has zero
  persistence anywhere - no repository for itself or `BankStatementLine`,
  and its `match()` method only mutates in-memory sets that don't
  survive past one request. The two features are deliberately *not*
  treated as equally scoped - Wave 1 (Working Capital) can start
  immediately, Wave 2 (Bank Reconciliation) is blocked on a real
  persistence-design decision, not effort. Nothing built yet.
