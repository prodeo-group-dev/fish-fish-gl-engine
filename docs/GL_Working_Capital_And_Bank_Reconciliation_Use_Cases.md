# Working Capital & Bank Reconciliation — Use Cases

Companion to `GL_Working_Capital_And_Bank_Reconciliation_Software_Requirements_Specification.md`.
UC-BANKREC-* use cases are written against the SRS §2.2 decision points
as placeholders for the *shape* of the workflow - their exact mechanics
(how a statement is uploaded, how a match is persisted) depend on that
still-open decision and will likely need revision once it's made.

## UC-WC-01: View a Company's Working Capital

**Actor**: Any Membership holder with read access to a Company.

**Preconditions**: The actor has a valid, verified session and a
Membership on the target Company (`authorizeTenantForRead`).

**Main flow**:
1. Actor calls `GET /companies/{companyId}/reports/working-capital`.
2. System verifies the claimed tenant and read access.
3. System computes `WorkingCapital.of()` from the Company's current
   Accounts and posted `JournalEntry` data and returns
   totalCurrentAssets/totalCurrentLiabilities/workingCapital.

**Alternate flow — no Accounts configured**: Same `IllegalArgumentException`-
on-empty-list behavior `WorkingCapital.of()` already enforces - the use
case should translate this to a clear 409/400, not let it surface as a
raw 500 (confirm the exact response shape against
`ComputeBalanceSheetUseCase`'s own precedent for an equivalent "no Chart
of Accounts configured" case at build time).

**Postconditions**: None (read-only).

**Traces to**: FR-WC-01, FR-WC-02, NFR-WC-01.

---

## UC-BANKREC-01: Start a Bank Reconciliation (shape depends on SRS §2.2)

**Actor**: A Membership holder with write access to a Company.

**Preconditions**: A Cash/Bank Account exists; a bank statement (date,
ending balance, lines) is available to supply, however §2.2 decides
statement ingestion works (manual entry vs. bulk upload).

**Main flow**: Actor supplies the Account, statement date, ending
balance, and statement lines; system persists a new `BankReconciliation`
with zero matches yet.

**Traces to**: FR-BANKREC-01.

---

## UC-BANKREC-02: Match a statement line to a posted JournalEntry

**Actor**: Same as UC-BANKREC-01.

**Preconditions**: An existing, not-yet-fully-reconciled
`BankReconciliation`; an unmatched statement line and an eligible
unmatched `JournalEntry` on it.

**Main flow**:
1. Actor supplies a statement line id and a `JournalEntry` id.
2. System re-derives (or loads) the reconciliation's current state,
   applies `BankReconciliation.match()`'s existing validation (same
   Account/side/amount checks already built), and persists the result.

**Alternate flow — already matched / amount mismatch**: Same
`ValidationResult.failure(...)` messages `match()` already produces,
surfaced as a 400/409, not silently accepted.

**Postconditions**: The match persists - a later UC-BANKREC-03 call
sees it without the caller resupplying anything.

**Traces to**: FR-BANKREC-02, NFR-BANKREC-01.

---

## UC-BANKREC-03: View a Bank Reconciliation's current state

**Actor**: Any Membership holder with read access to the Company.

**Preconditions**: An existing `BankReconciliation`.

**Main flow**: Actor requests the reconciliation; system returns
matched/unmatched statement lines, matched/unmatched entries, and
`isFullyReconciled`.

**Postconditions**: None (read-only).

**Traces to**: FR-BANKREC-03.
