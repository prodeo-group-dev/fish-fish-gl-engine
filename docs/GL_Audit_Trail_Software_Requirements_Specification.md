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

**Revised 2026-10-03 after the fail-closed decision (§2.2 Q1) — the
original wiring point named below was wrong and is kept here, struck
through, so the correction is visible rather than silently rewritten.**

~~The natural, lowest-risk wiring point is `Auth.kt`'s own
`authorizeTenantForWrite`/`authorizeTenantForAdmin` functions — every
write route already calls one of these and receives back an
`AuthorizedCaller` with the actor's email, plus already has `tenantId`/
`companyId` in scope. Recording an audit entry as a thin addition to
that shared authorization path (rather than hand-instrumenting each of
the ~30+ individual write route handlers) means no route can forget to
audit itself, mirroring how `authorizeTenantForWrite` itself already~~

**Why that's wrong under fail-closed**: `authorizeTenantForWrite`/
`Admin` run *before* the use case's own business logic executes. If the
audit entry were written there and the write succeeded, but the use
case itself later rejects the action for its own reasons (e.g. "Period
not open," "invalid amount"), the result is an audit entry for an
action that never actually happened — a stray false positive. That's a
different, lesser failure mode than fail-closed is meant to prevent,
but it's still wrong, and it would make the audit log unreliable in the
opposite direction (recording things that didn't happen, rather than
missing things that did).

**Corrected approach**: the audit entry write and the use case's own
persistence write (e.g. `journalEntryRepository.save()`,
`accountRepository.save()`) must happen **in the same database
transaction**, committed atomically. If either fails, both roll back —
true fail-closed, not "write audit first and hope." Per Femi's own
framing of this (2026-10-03): *"Double entry is ALL or nothing"* - the
audit write is held to the exact same atomicity standard
`JournalEntry.create()`'s own balanced-debit/credit invariant already
is. This means the real wiring point is inside each use case's own
persistence step (where the business write already happens), not the
pre-flight authorization layer — `authorizeTenantForWrite`/`Admin`
still resolve *who* the actor is (unchanged), but *recording* the audit
entry moves to where the business write itself commits. This is a
materially larger change than originally scoped: each of the ~30+ write
use cases needs its own audit write added inside its existing
transaction, not one shared authorization-layer hook. Wave 2 in the
backlog is revised accordingly.

**Confirmed technical mechanism** (checked directly against
`ExposedJournalEntryRepository`, not assumed): each repository's
`save()` opens its own `transaction { }` block today - every write is
already its own standalone transaction. Exposed's `transaction { }`
reuses an already-open transaction when called from within one (rather
than always starting a new one), so wrapping a use case's *existing*
business `save()` call and the *new* audit-entry `save()` call inside
one outer `transaction { }` at the use-case level makes both commit or
roll back together, with no change needed to either repository's own
`save()` implementation.
makes it impossible for a route to forget tenant scoping.

This requires each call site to also supply the `AuditAction` and
entity-type/id being acted on — a small, explicit addition to each
route's existing authorization call, not a hidden/implicit side effect
a future reader would have to guess at.

### 2.2 Open questions

Per this project's "park, don't guess" convention — these are real
trade-offs, not implementation details.

1. **Fail-open vs. fail-closed on audit-write failure — Decided
   2026-10-03: fail-closed.** Direct instruction, given the risk:
   "The risk is high if it is Fail-Open. It has to be Fail-Closed." If
   the audit entry can't be written, the business operation it would
   have recorded does not happen either — no un-audited action is ever
   allowed to complete, even at the cost of availability. See §2.1's
   revised architectural approach (same-transaction commit) for how
   this is actually achieved, not just declared.
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

- **NFR-AUDIT-01**: Fail-closed (decided, §2.2 Q1). A write operation
  MUST NOT complete if its corresponding `AuditLogEntry` fails to
  persist - enforced by committing both in the same database
  transaction (§2.1), not by a best-effort "write audit, then proceed
  regardless" sequence.
- **NFR-AUDIT-02**: Writing an audit entry must not introduce an N+1 or
  materially regress latency on hot write paths — a single denormalized
  insert per action, no joins required at write time.
- **NFR-AUDIT-03**: The read route enforces the same per-Company tenancy
  isolation discipline as every other route in this codebase
  (`resolveTenantForCompany`/`verifyClaimedTenant`/`authorizeTenantForAdmin`
  or equivalent — Owner-Admin-only per FR-02, stricter than the
  Write-level access this SRS's own writes require).

## 7. Open Issues

See §2.2. Q1 (fail-open vs. fail-closed) is now decided - fail-closed,
achieved via same-transaction commit (§2.1). Q2-Q4 remain open; none of
them block Wave 1 (the domain layer and persistence), Q2 blocks Wave 5
(retention/export), Q3/Q4 don't block any wave in this backlog. Do not
guess an answer to Q2-Q4; confirm with Femi first.
