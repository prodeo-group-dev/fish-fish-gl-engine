# GL Audit Trail — Use Cases

Companion to `GL_Audit_Trail_Software_Requirements_Specification.md`.
UC-AUDIT-01 is internal/automatic (no human actor initiates it directly
— it's triggered as a side effect of every other write use case in the
system), included here for completeness since it's the load-bearing
mechanism every other use case in this document depends on.

## UC-AUDIT-01: Record an audit entry for a write operation

**Actor**: The system itself, triggered automatically within
`authorizeTenantForWrite`/`authorizeTenantForAdmin` (Auth.kt), not a
standalone caller-facing endpoint.

**Preconditions**: A write route has already resolved a valid
`AuthorizedCaller` (human) or is a recognized system-generated posting
path (FR-06).

**Main flow**:
1. The calling route supplies the `AuditAction` and entity type/id
   being acted on, alongside its existing `authorizeTenantForWrite`/
   `Admin` call.
2. The shared authorization helper constructs an `AuditLogEntry` from
   the already-resolved actor email, `tenantId`, `companyId`, the
   supplied action/entity info, and the current timestamp.
3. The entry is persisted via `AuditLogRepository.save()`.
4. The route's own business logic proceeds (or is blocked, per
   NFR-AUDIT-01's fail-open/fail-closed decision, if the save itself
   fails).

**Alternate flow (system-generated posting)**: Step 1's actor is the
well-known system sentinel (FR-06) rather than a human
`AuthorizedCaller`; everything else proceeds identically.

**Postconditions**: Exactly one durable, immutable `AuditLogEntry`
exists for the write operation.

**Traces to**: FR-AUDIT-01, FR-AUDIT-04, FR-AUDIT-06, NFR-AUDIT-01,
NFR-AUDIT-02.

---

## UC-AUDIT-02: Owner-Admin views the Company's audit log

**Actor**: A Membership holder with Owner-Admin access level on a
Company.

**Preconditions**: The actor has a valid, verified session and an
Owner-Admin-level Membership on the target Company (per
`authorizeTenantForAdmin` or an equivalent stricter check — see
FR-AUDIT-02's note that this is deliberately stricter than the general
read-access level most reports use).

**Main flow**:
1. Actor calls `GET /companies/{companyId}/audit-log` with no filters.
2. System verifies the claimed tenant and the actor's Owner-Admin
   access level for that Company.
3. System returns the most recent page of `AuditLogEntry` records for
   that Company, newest first.

**Alternate flow — insufficient access level**: A Membership holder
below Owner-Admin calls the same route → `403 Forbidden`, same shape as
every other access-level-gated route in this codebase.

**Postconditions**: None (read-only).

**Traces to**: FR-AUDIT-02, NFR-AUDIT-03.

---

## UC-AUDIT-03: Owner-Admin filters the audit log

**Actor**: Same as UC-AUDIT-02.

**Preconditions**: Same as UC-AUDIT-02.

**Main flow**:
1. Actor calls `GET /companies/{companyId}/audit-log` with any
   combination of `from`/`to` (date range), `entityType`, and/or
   `action` query parameters.
2. System applies the supplied filters server-side (not a bulk fetch
   filtered client-side — the log is expected to grow large over a
   Company's lifetime) and returns the matching page.

**Alternate flow — malformed filter value**: An unparseable date or an
`action` value that doesn't match a real `AuditAction` → `400 Bad
Request`, same convention as every other route's query/body
validation in this codebase.

**Postconditions**: None (read-only).

**Traces to**: FR-AUDIT-02.

---

## UC-AUDIT-04: A system-generated posting is recorded with a system actor

**Actor**: The system (e.g., `RecordFixedAssetDepreciationUseCase`
running on a schedule, or any other automated posting path with no
authenticated human caller in the originating request).

**Preconditions**: A system-generated posting use case executes
successfully.

**Main flow**: Identical to UC-AUDIT-01's alternate flow — the audit
entry's actor is the well-known system sentinel rather than a human
email.

**Postconditions**: The resulting `AuditLogEntry` is indistinguishable
in structure from a human-attributed one, filterable the same way
(e.g., "show me everything the system did, not a human" is a query
against `actorEmail = <system sentinel>`, not a separate code path).

**Traces to**: FR-AUDIT-06.
