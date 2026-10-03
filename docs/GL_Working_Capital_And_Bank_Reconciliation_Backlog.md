# Working Capital & Bank Reconciliation — Dependency-Ordered Backlog

Companion to `GL_Working_Capital_And_Bank_Reconciliation_Software_Requirements_Specification.md` /
`..._Use_Cases.md`. Nothing built yet. Two independent tracks - Working
Capital (Wave 1) is fully unblocked; Bank Reconciliation (Wave 2) is
blocked on a real design decision (SRS §2.2), not effort.

**Coordination**: GL-internal, single-service work for Wave 1. Wave 2
is also GL-internal (no cross-service wire change), but is large enough
in scope that a coordination row is worth claiming when it actually
starts, same judgment call as the Audit Trail backlog's own note.

| # | Item | Depends on | Status |
|---|---|---|---|
| 1.1 | ~~`ComputeWorkingCapitalUseCase`~~ | — | **Done** (GL `58642e0`, local, handed to CM). |
| 1.2 | ~~`GET /companies/{companyId}/reports/working-capital`~~ | 1.1 | **Done** - added to `reportsRoutes()` alongside its three siblings, `WorkingCapitalResponseDto` in `Dtos.kt`. |
| 1.3 | ~~Wire into `Application.kt`~~ | 1.2 | **Done** - `computeWorkingCapitalUseCase` is a defaulted `fishModule` param (constructed from repos already in scope), not a new required one, so none of the ~25 existing test fixtures needed touching. 3 new tests in `ReportsRoutesTest.kt`, full suite green. |
| 2.0 | **Decision gate**: resolve SRS §2.2's four sub-decisions (repository shape, statement-ingestion mechanism, match-persistence model) with Femi before any code in this wave. | — | Blocked on decision |
| 2.1 | `BankReconciliationRepository`/statement-line persistence, per whatever §2.2 decides | 2.0 | Not started |
| 2.2 | `StartBankReconciliationUseCase` (or equivalent, naming depends on §2.2's ingestion decision) | 2.1 | Not started |
| 2.3 | `MatchBankReconciliationLineUseCase` wrapping `BankReconciliation.match()`'s existing validation against the now-persisted state | 2.1 | Not started |
| 2.4 | Read route(s) for reconciliation state (UC-BANKREC-03) | 2.1 | Not started |

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
