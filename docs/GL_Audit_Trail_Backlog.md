# GL Audit Trail — Dependency-Ordered Backlog

Companion to `GL_Audit_Trail_Software_Requirements_Specification.md` /
`GL_Audit_Trail_Use_Cases.md`. Same Wave-numbered, dependency-ordered
table format as `docs/GL_POP_IM_SOP_Backlog.md`. Nothing built yet —
this is the task breakdown, not a status report.

**Wave 2's wiring approach was corrected 2026-10-03** (see SRS §2.1)
after the fail-open/fail-closed question was decided - **fail-closed**,
direct instruction: *"The risk is high if it is Fail-Open. It has to be
Fail-Closed,"* and confirmed again separately: *"Double entry is ALL or
nothing"* - the audit write is held to the same atomicity standard as
`JournalEntry.create()`'s own balance invariant. The original plan (a
shared hook inside `authorizeTenantForWrite`/`Admin`) can't deliver
that - it runs before business logic, so a later business-logic
rejection would leave a stray audit entry for an action that never
happened. The corrected shape: each use case wraps its own existing
business `save()` and the new audit `save()` in one outer
`transaction { }` block (confirmed directly that Exposed's
`transaction { }` nests correctly into an already-open one, so this
needs no change to either repository's own `save()` implementation) -
materially larger surface area than the original one-hook plan, since
it touches each of the ~30+ write use cases individually rather than
one shared layer.

**Coordination**: this is GL-internal, single-service work — no row
needed in `docs/GL_POP_IM_SOP_Coordination.md` per that log's own
carve-out. Nothing in Wave 2 touches a shared cross-service interface
(`Auth.kt`'s own functions are untouched under the corrected approach).

| # | Item | Depends on | Status |
|---|---|---|---|
| 1.1 | ~~`AuditLogEntry` domain class~~ | — | **Done** (GL `b0a0a51`, local, handed to CM). 6 unit tests green. |
| 1.2 | ~~`AuditLogRepository` interface~~ | 1.1 | **Done** (GL `b0a0a51`). |
| 1.3 | ~~Flyway migration + Exposed table + `ExposedAuditLogRepository`~~ | 1.2 | **Built, compiles clean** (GL `b0a0a51`) - **not yet verified live against real Postgres**. A disposable container spun up to test it collided with a pre-existing native Postgres already on port 5432 (this project's own documented local dev DB, per `.env.example`'s `fish_dev` role) - credentials unknown, not guessed around. 7-test `AuditLogRepositoryIntegrationTest` exists and compiles, needs a real run before Wave 2 trusts it. |
| 1.4 | ~~`FakeAuditLogRepository` test double~~ | 1.1 | **Done** (GL `b0a0a51`) - mirrors the Exposed repository's own filter/pagination logic. |
| 2.1 | Pick and build one representative write use case first (e.g. `PostJournalEntryUseCase`) wrapping its existing `journalEntryRepository.save()` and a new `auditLogRepository.save()` call in one outer `transaction { }` - prove the fail-closed shape (a forced audit-write failure must roll back the business write too, tested explicitly) before repeating it ~30 more times. | 1.1-1.4 | Not started |
| 2.2 | Apply the proven shape from 2.1 to every remaining write use case (`RecordSaleUseCase`, `RecordCollectionUseCase`, `RecordSupplierObligationUseCase`/`RecordSupplierPaymentUseCase`, `RecordInventoryReceiptUseCase`/`RecordInventoryIssueUseCase`, `CreateAccountUseCase`, `CreateFixedAssetUseCase`, Period open/close, Tenant/Company admin operations, etc. - enumerate the full list directly against current code before starting, don't assume this list is exhaustive). Mechanical but large-surface-area; recommend one file/use case at a time with the full suite re-run after each, same discipline as the Creditor->Supplier rename. | 2.1 | Not started |
| 3.1 | `ComputeAuditLogUseCase` (or equivalent) implementing the query/filter logic (date range, entity type, action) against `AuditLogRepository.findByCompany()`, per UC-AUDIT-02/03 | 1.2 | Not started |
| 3.2 | `GET /companies/{companyId}/audit-log` route, Owner-Admin-gated (stricter than the general read-access level - confirm the exact access-level check against `AccessLevel`'s current definition before building, don't assume `authorizeTenantForRead` is sufficient), paginated, DTOs in `Dtos.kt` | 3.1 | Not started |
| 4.1 | Wire the system-actor sentinel into every system-generated posting path (`RecordFixedAssetDepreciationUseCase`, `RemeasureLeaveAccrualUseCase`, ECL remeasurement, and any other `JournalSource.SYSTEM`-equivalent caller - audit this list directly against current code before building, don't assume it's only these three) per FR-AUDIT-06/UC-AUDIT-04 | 2.2 | Not started |
| 5.1 (Could) | CSV export of a date-ranged slice, reusing `ExportFormat`, per FR-AUDIT-05 | 3.2 | Not started, deferred unless requested |
| 5.2 | Retention policy decision + implementation (SRS §2.2 Q2) - explicitly parked, needs Femi's input, ties to the still-open "no documented backup/DR story" MVP flag | 1.3 | Parked, not started |

## Change log

- **2026-10-03 (GL session)**: Initial SPUTO pass (SRS + Use Cases +
  this backlog), per direct instruction, chosen over Departmental/
  Branch/Group Accounts reporting and the event-bus architecture
  question as the next scoping target. Grounded directly against code
  before writing requirements: confirmed `RequestContext` is never
  constructed anywhere outside its own file, confirmed `JournalEntry`
  has no actor field, confirmed every write route already resolves an
  `AuthorizedCaller(email, name)` via `authorizeTenantForWrite`/`Admin`
  (the chosen Wave 2 wiring point). Nothing built yet.
- **2026-10-03 (GL session, same day)**: SRS §2.2 Q1 decided -
  **fail-closed** (*"The risk is high if it is Fail-Open. It has to be
  Fail-Closed"*, confirmed again as *"Double entry is ALL or nothing"*).
  That decision invalidated the original Wave 2 wiring plan: a hook
  inside `authorizeTenantForWrite`/`Admin` runs before business logic,
  so it can't actually guarantee fail-closed (a later business
  rejection would leave a stray audit entry for an action that never
  happened). Corrected to: each write use case wraps its own existing
  business `save()` and a new audit `save()` in one outer
  `transaction { }`, confirmed directly against
  `ExposedJournalEntryRepository` that Exposed's `transaction { }`
  nests correctly into an already-open one. Wave 2 rewritten (2.1 proves
  the shape on one use case, 2.2 rolls it out to the rest) - larger
  surface area than originally scoped, but the only way to actually
  deliver what was decided. Still nothing built.
