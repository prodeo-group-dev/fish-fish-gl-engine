# GL Audit Trail — Software Requirements Specification

**Status**: SPUTO pass, 2026-10-03, per direct instruction following
`GL_MVP_Definition.md`'s own finding (flag #1): GL has no actual audit
trail of who did what, when, despite designed-but-dormant scaffolding
for exactly this already sitting in the codebase. Scope confirmed with
Femi directly before this pass (chosen over Departmental/Branch/Group
Accounts reporting and the event-bus architecture question, both also
real, both deferred).

## 1. Introduction

### 1.1 Purpose

GL is a General Ledger — the system of record for a Company's money.
Its core value proposition rests on being trustworthy: not just
mathematically correct (the balanced-entry invariant, reversal-only
correction), but *accountable* — able to answer "who did this, and
when" for any posting, Chart of Accounts change, or Period operation.
Today it cannot. This SRS scopes the minimum real audit trail that
closes that gap.

### 1.2 Scope

**Confirmed directly against code before writing a single requirement
below, not assumed:**

- `domain/common/audit_action.kt` defines a real, thoughtfully-designed
  `AuditAction` enum (CREATED/UPDATED/POSTED/REVERSED/OPENED/CLOSED/
  LOGIN/PERMISSION_GRANTED/etc., 25 values across CRUD, state-transition,
  Period, Tenant, security, data, system, and access-tracking
  categories) — clearly built with a real audit log in mind.
- `domain/common/request_context.kt` defines `RequestContext` (userId,
  sessionId, requestId, ipAddress, userAgent, timestamp), with its own
  KDoc showing the intended call shape:
  `auditService.log(action = AuditAction.POSTED, context = context, ...)`.
- **Neither is wired to anything.** Grepped the full `main/kotlin` tree:
  zero construction sites for `RequestContext` anywhere outside its own
  file; zero call sites that write an audit log entry anywhere in the
  codebase. This scaffolding dates to the original 2026-08-11 skeleton
  build and was never connected to the web layer that came later.
- `domain/ledger/journal_entry.kt` itself carries **no actor field at
  all** — a posted `JournalEntry` records *what* happened (balanced
  lines, a date, a `JournalSource`) but not *who* posted it. This is a
  deeper gap than "no audit log route" — even a bolted-on audit log
  can't retroactively attribute historical postings to an actor unless
  the link is captured going forward.
- `RequestContext`'s own design (a `userId: UUID`) predates the
  Tenancy/Administration extraction (`docs/Tenancy_Administration_Extraction_DDD_Design.md`,
  2026-09-05) that moved `User`/`Membership` out of GL into EA. GL no
  longer has a local `userId` to attach to anything — every write route
  today resolves an `AuthorizedCaller(email: String, name: String)` via
  `authorizeTenantForWrite`/`authorizeTenantForAdmin`/etc. (`Auth.kt`).
  **The actor identity shape for this SRS is email, not a UUID** —
  `RequestContext` as originally designed is stale and is not reused
  as-is.

**In scope**: a real, append-only audit log for GL's own write surface
(every route already gated by `authorizeTenantForWrite`/`Admin`), plus
system-generated postings (depreciation, ECL remeasurement, leave
accrual, etc. — `JournalSource`-tagged automated entries with no human
actor), plus a read-only, Owner-Admin-gated query route.

**Out of scope**: POP/SOP/IM/HR/EA's own equivalent gap, if any (not
checked here — a candidate platform-wide follow-up once GL's shape is
proven, same rollout pattern already used for the EA-membership-check
generalization). Retrofitting historical `JournalEntry` records with a
reconstructed actor (impossible — the data was never captured). A UI
for browsing the log (WEB's own concern, per
`feedback_frontend_routes_through_web` — this SRS scopes the backend
API only).

### 1.3 Definitions

- **Audit log entry**: one immutable record of "actor X did action Y to
  entity Z at time T, for Company C."
- **System actor**: the well-known, non-human actor attributed to
  automated postings (depreciation, ECL, etc.) that have no
  authenticated human caller.

## 2. Overall Description

### 2.1 Architectural approach

The natural, lowest-risk wiring point is `Auth.kt`'s own
`authorizeTenantForWrite`/`authorizeTenantForAdmin` functions — every
write route already calls one of these and receives back an
`AuthorizedCaller` with the actor's email, plus already has `tenantId`/
`companyId` in scope. Recording an audit entry as a thin addition to
that shared authorization path (rather than hand-instrumenting each of
the ~30+ individual write route handlers) means no route can forget to
audit itself, mirroring how `authorizeTenantForWrite` itself already
makes it impossible for a route to forget tenant scoping.

This requires each call site to also supply the `AuditAction` and
entity-type/id being acted on — a small, explicit addition to each
route's existing authorization call, not a hidden/implicit side effect
a future reader would have to guess at.

### 2.2 Open questions (flagged, not picked)

Per this project's "park, don't guess" convention — these are real
trade-offs, not implementation details, and need Femi's decision before
Wave 2 (the wiring step) locks in a shape:

1. **Fail-open vs. fail-closed on audit-write failure.** If the audit
   insert itself fails (DB hiccup, etc.), should the business operation
   it's auditing also fail (strict — guarantees no un-audited action,
   but makes audit-log availability a new single point of failure for
   the whole write surface), or succeed anyway with the failure only
   logged (available — but can silently produce a gap in the trail)?
   No default is assumed here.
2. **Retention policy.** Keep every entry forever, or age out after N
   years? Ties into the still-open "no documented backup/DR story"
   finding from `GL_MVP_Definition.md` — an audit log with no retention
   policy is itself an unbounded-growth and backup-scope question.
3. **Should reading the audit log itself produce a `VIEWED` audit
   entry?** Recommended: no, for v1 — turtles-all-the-way-down scope
   creep with no clear requirement driving it. Revisit if a real
   compliance requirement (SOX, an auditor's actual ask) names it.
4. **Platform-wide rollout.** Should POP/SOP/IM/HR adopt the same
   pattern once GL's is proven? Not this SRS's call — flagged as a
   follow-up for CM/the relevant sessions once Wave 1-3 below ship and
   the shape is validated in production, same incremental-rollout
   precedent as the EA-membership-authorizer generalization
   (`docs/Service_Account_Identity_And_EA_Membership_Design_Note.md`).

## 3. System Features

### FR-AUDIT-01 (Must)

Every write-route call protected by `authorizeTenantForWrite`/
`authorizeTenantForAdmin` records exactly one `AuditLogEntry` capturing:
actor email, `AuditAction`, entity type (a short string, e.g.
`"JournalEntry"`, `"Account"`, `"Period"`), entity id, `CompanyId`,
`TenantId`, and a timestamp. Persisted durably (a real table, not just
a CloudWatch log line — CloudWatch logs are searchable but not a
queryable, structured audit record with its own access control).

### FR-AUDIT-02 (Must)

A read-only `GET /companies/{companyId}/audit-log` route, gated at
Owner-Admin access level specifically (stricter than the general
`authorizeTenantForRead` used by every other reporting route — an audit
log is itself sensitive: it reveals who did what, which is not
information every READ_ONLY Membership should see). Paginated (reusing
the existing `PaginatedResult`/`OrderBy` common types), filterable by
date range, entity type, and `AuditAction`.

### FR-AUDIT-03 (Should)

Where cheaply available at the call site, capture a structured "detail"
field (e.g., for `UPDATED`, a short human-readable summary of what
changed) — not a blanket requirement for every action type, and never
at the cost of delaying Wave 1-3 below.

### FR-AUDIT-04 (Must)

Audit log entries are append-only — no update or delete route is ever
built for them, mirroring `JournalEntry`'s own reversal-only correction
philosophy. An audit log that could itself be edited defeats its
purpose.

### FR-AUDIT-05 (Could)

CSV export of a date-ranged audit log slice, reusing the existing
`ExportFormat` common type. Deferred unless specifically requested —
not required to close the MVP flag.

### FR-AUDIT-06 (Must)

System-generated postings (`RecordFixedAssetDepreciationUseCase`,
`RemeasureLeaveAccrualUseCase`, ECL remeasurement, etc. — anything
posted with no authenticated human caller in the request) still produce
an audit entry, attributed to a well-known system actor (e.g.,
`"system@theprodeogroup.com"` or an equivalent sentinel), not silently
skipped just because `AuthorizedCaller` doesn't apply.

## 4. Data Requirements

`AuditLogEntry`: id, tenantId, companyId, actorEmail, action
(`AuditAction`), entityType (string), entityId (string — not every
audited entity shares one id type), detail (nullable string, FR-03),
occurredAt (Instant). Indexed on (companyId, occurredAt) for the
read route's primary access pattern.

## 5. External Interfaces

`GET /companies/{companyId}/audit-log?from=&to=&entityType=&action=&cursor=`
— same query-param pagination shape already established elsewhere in
this codebase (check `PaginatedResult`'s existing consumers for the
exact convention before inventing a new one in Wave 3).

## 6. Non-Functional Requirements

- **NFR-AUDIT-01**: See Open Question 1 above — fail-open vs.
  fail-closed is a locked decision point before Wave 2, not an
  implementation detail to default silently.
- **NFR-AUDIT-02**: Writing an audit entry must not introduce an N+1 or
  materially regress latency on hot write paths — a single denormalized
  insert per action, no joins required at write time.
- **NFR-AUDIT-03**: The read route enforces the same per-Company tenancy
  isolation discipline as every other route in this codebase
  (`resolveTenantForCompany`/`verifyClaimedTenant`/`authorizeTenantForAdmin`
  or equivalent — Owner-Admin-only per FR-02, stricter than the
  Write-level access this SRS's own writes require).

## 7. Open Issues

See §2.2. None of the four questions there block Wave 1 (the domain
layer and persistence) — they block Wave 2 (the actual wiring decision)
and Wave 5 (retention/export). Do not guess an answer when Wave 2
starts; confirm with Femi first.
