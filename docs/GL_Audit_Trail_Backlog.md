# GL Audit Trail — Dependency-Ordered Backlog

Companion to `GL_Audit_Trail_Software_Requirements_Specification.md` /
`GL_Audit_Trail_Use_Cases.md`. Same Wave-numbered, dependency-ordered
table format as `docs/GL_POP_IM_SOP_Backlog.md`. Nothing built yet —
this is the task breakdown, not a status report.

**Before starting Wave 2**: confirm the SRS's §2.2 Open Question 1
(fail-open vs. fail-closed on audit-write failure) with Femi. Wave 1
doesn't depend on that answer; Wave 2 does.

**Coordination**: this is GL-internal, single-service work — no row
needed in `docs/GL_POP_IM_SOP_Coordination.md` per that log's own
carve-out, unless Wave 2's wiring point in `Auth.kt` is judged risky
enough to warrant a heads-up to peers (it changes a function every
sibling-calling route already depends on, even though the change is
additive). Judgment call at Wave 2 start, not pre-decided here.

| # | Item | Depends on | Status |
|---|---|---|---|
| 1.1 | `AuditLogEntry` domain class (append-only, `internal reconstitute()` matching every other aggregate's persistence pattern) + `AuditAction`/entity-type/id/actorEmail/tenantId/companyId/detail/occurredAt fields, per the SRS §4 Data Requirements. No mutators beyond construction - FR-AUDIT-04's immutability is enforced at the type level, not just by convention. | — | Not started |
| 1.2 | `AuditLogRepository` interface (`save()`, `findByCompany(companyId, filters, pagination)` — no `update`/`delete` method exists at all, same enforcement-by-omission MembershipRepository-style precedent used elsewhere in this codebase) | 1.1 | Not started |
| 1.3 | Flyway migration + Exposed table (`audit_log_entries`, indexed on `(company_id, occurred_at)` per NFR-AUDIT-02/the SRS §4 access pattern) + `ExposedAuditLogRepository` | 1.2 | Not started |
| 1.4 | `FakeAuditLogRepository` test double, matching every other `Fake*Repository` in `EcosystemRepositoryFakes.kt`/equivalent | 1.1 | Not started |
| 2.1 | **Decision gate**: confirm SRS §2.2 Q1 (fail-open vs. fail-closed) with Femi before writing any code in this wave. | 1.1-1.4 | Blocked on decision |
| 2.2 | Extend `authorizeTenantForWrite`/`authorizeTenantForAdmin` (`Auth.kt`) to accept an `AuditAction` + entity type/id and write an `AuditLogEntry` as part of the existing call, per the SRS §2.1 architectural approach - the single shared wiring point, not per-route hand-instrumentation. Exact function signature (new optional params with defaults vs. a new `*WithAudit` variant callers opt into) is an implementation-level call, not scoped further here. | 2.1 | Not started |
| 2.3 | Thread the new audit parameters through every existing write route (~30+ call sites across `JournalEntryRoutes.kt`, `RecordSaleAndCollectionRoutes.kt`, `RecordSupplierObligationAndPaymentRoutes.kt`, `RecordInventoryReceiptAndIssueRoutes.kt`, `FixedAssetRoutes.kt`, `TenantRoutes.kt`, etc.) - mechanical but large-surface-area work; recommend doing it file-by-file with the full suite re-run after each to catch any missed call site immediately, same discipline as the Creditor->Supplier rename. | 2.2 | Not started |
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
