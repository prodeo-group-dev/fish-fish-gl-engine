# GL Engine — Minimum Viable Development Level

**Status: first-pass answer, 2026-10-02**, per the platform-wide instruction
(via CM) to determine the smallest real, working slice of each service's
domain that could actually go live — not full backlog completion.
Cross-checked directly against current code (routes, use cases, domain
classes actually wired end to end), not assumed from memory or from the
backlog's own framing. Mirrors `HR/docs/HR_MVP_Definition.md`'s shape
(floor vs. enhancement, then real pre-go-live flags distinct from backlog
priority).

## The headline: GL's own ledger MVP is substantially already met

A Company could run real double-entry bookkeeping on GL today, entirely by
hand, and get real financial statements out the other end:

- **Chart of Accounts** — `CreateAccountUseCase`, typed `AccountType`/
  `AccountClassification`, Company-scoped. Built, live.
- **Periods** — `Period.open()`/`close()`, with `PeriodStatus` transition
  rules enforced before any posting is accepted into a period.
- **Manual journal entries, posted and reversed** — `PostJournalEntryUseCase`
  (`POST /journal-entries`) enforces the balanced-entry invariant
  (`JournalEntry.create()`); `JournalEntry.reverse()` is the only correction
  path (no destructive edit of a posted entry) — this is itself a structural
  audit property, not a feature still to build.
- **Opening balances** — `RecordOpeningBalanceUseCase`, so a Company isn't
  stuck starting from zero.
- **The three core financial statements** — Balance Sheet, Profit & Loss,
  Statement of Cash Flows (`GET /companies/{companyId}/reports/{...}`),
  all derived from posted `JournalEntry` data, not hand-maintained.
- **Multi-tenancy/auth** — Tenant/Company/Membership/Role, per-request
  `companyId` authorization (confirmed clean in the 2026-09-28 cross-service
  audit — GL never had the per-Company RBAC bug IM/POP had).

This is a genuine minimum viable general ledger: a bookkeeper could close a
month on GL using only the pieces above, with nothing hand-waved. Everything
below this line is real, valuable, shipped work — but it is *enhancement*
on top of an already-crossed floor, not part of the floor itself.

## What's not built but can be routed around manually

Consistent with HR's own MVP framing — these are automation/convenience
gaps a Company can work around at low volume, not hard blockers to a first
real go-live:

- **Integration postings from SOP/POP/IM/HR** (`RecordSaleUseCase`,
  `RecordVendorObligationUseCase`, `RecordInventoryReceiptUseCase`,
  `RecordPayRunUseCase`, etc.) are the *real* product once a Company adopts
  the wider ecosystem — but every one of them ultimately produces the same
  shape of balanced journal entry `PostJournalEntryUseCase` already accepts
  directly. A Company using GL standalone (no SOP/POP/IM/HR) can post every
  sale, purchase, receipt, and pay run by hand through the same manual entry
  path. Slower, not blocked.
- **AR/AP aging and balance schedules** (`CustomerBalancesRoutes`,
  `VendorBalancesRoutes`, and the two new aging routes shipped today,
  `d29ce6d`/`5c5c8cb`) are genuinely useful reporting, reusing already-posted
  ledger data — but a Company can track who owes/is owed what in a
  spreadsheet alongside GL without blocking a first go-live.
- **Fixed Asset Register / depreciation** — a Company can track assets and
  post depreciation manually via the same journal-entry path.
- **Bank Reconciliation** — can be done manually outside the system at low
  transaction volume.
- **Corporate Income Tax computation** (`ComputeTaxUseCase`) — same
  reasoning HR applies to PAYE/NI: an accountant computes it externally,
  enters the resulting journal entry by hand. Automating this is an
  accuracy-at-volume concern, not a go/no-go gate.
- **VAT** — not built anywhere in the platform (`TaxType` has no VAT member
  at all, confirmed directly, same finding `docs/IE/IE_VAT_MVP_Design.md`
  already made). Out of scope for a jurisdiction where the Company's own
  bookkeeper handles VAT externally; a hard blocker only for a jurisdiction
  where VAT MVP work is specifically being built (RoI — already its own,
  separate, in-progress design).
- **Opening Figures bulk CSV upload** — single-record entry (already built
  for every domain) still works, just slower for onboarding a Company with
  an established trading history.
- **Raw Trial Balance as its own report** — confirmed by direct check: no
  dedicated route exists (`ReportsRoutes.kt` only serves balance-sheet/
  profit-and-loss/cash-flow; `TrialBalance` itself is consumed internally
  by `BalanceSheet.of()` but never returned directly to a caller). A real
  but minor completeness gap — Balance Sheet + P&L together already convey
  what a non-accountant needs; a raw Trial Balance is mainly a pre-close
  checking tool for an accountant, not a go-live blocker.
- **Departmental/Branch/Group Accounts** — tag-only or unbuilt
  (`project_gl_engine_reporting_scope`), genuinely unscoped future work,
  not required for a single-Company MVP.

## Real pre-go-live flags, not backlog nice-to-haves

Unlike the automation items above, these aren't things a Company can simply
route around without a real trust, legal, or data-loss exposure once this
switches from Development to Live (`project_development_to_live_switch`).
Flagging for Femi's confirmation rather than asserting these are blocking —
same "don't guess, don't fabricate" discipline HR applied to its own two
flags.

1. **No audit trail of who changed what, when — confirmed by direct code
   search, 2026-10-02.** `AuditAction` (domain/common/audit_action.kt) is
   declared and referenced once more in `RequestContext`, but nothing in
   the codebase ever constructs or persists an actual audit log entry from
   it — grepped across all of `src/main/kotlin`, zero write sites. Posted
   `JournalEntry`s are themselves immutable (reversal-only correction, no
   destructive edit), which is a real structural integrity property — but
   that's a different guarantee from "who posted this, and who approved
   it." For a product whose entire value proposition is being a trustworthy
   system of record, the absence of any actual user-action audit log is a
   genuine candidate for "needed before a real go-live," not just a nice
   future addition — on par with HR's missing-payslip finding in kind, if
   not necessarily in legal mandate (no specific statute requires it the
   way UK payslip law does; the case here is trust/governance, not a known
   legal requirement).
2. **No database-level tenant isolation (RLS) — already investigated and
   deliberately not built**, per `docs/Database_Tenant_Isolation_RLS_Scope.md`
   (2026-09-17ish, cross-referenced from the external code review). Blast
   radius confirmed GL/EA/HR specifically (POP/SOP/IM are physically
   isolated by separate per-service databases already). The prior decision
   was to hold off building this once the concrete app-layer bugs were
   closed, tracking it as deliberate future work rather than an active gap
   — restating it here because "GLaaS stores multiple customers' real
   financial data in shared tables with app-layer-only isolation" is
   exactly the kind of thing a real paying customer or their auditor would
   ask about directly, and the prior decision to defer it predates this
   session's "what can actually go live" framing.
3. **No documented backup/DR story** (`docs/GL_Production_Readiness_Assessment.md`,
   still open as of that doc's last update). For most services this is
   ordinary production hygiene; for a General Ledger specifically — the
   literal system of record for a Company's money — the absence of a known
   recovery plan undermines the product's core promise in a way it wouldn't
   for, say, a reporting dashboard. Worth distinguishing from the
   Assessment's other still-open items (no metrics/tracing, no rate
   limiting, minimal HikariCP tuning, no dependency-vulnerability scanning,
   no graceful shutdown, no API versioning) — those are real but ordinary
   production-hardening debt, not specific to what makes a ledger a ledger.

## What this means for sequencing

Nothing here changes `docs/GL_POP_IM_SOP_Backlog.md`'s own priorities or
waves. What this pass changes is the *framing*: items 1-3 above are worth
treating as "confirm with Femi before calling a real go-live," not just
"somewhere in the backlog" — the same distinction HR drew for its own two
flags. Everything in "routed around manually" remains real, queued,
valuable work; this doc doesn't close any of it.

## Not addressed here

Jurisdiction-specific go-live criteria (RoI's VAT MVP, UK/NI specifics) are
already their own documents (`docs/IE/IE_MVP_Definition.md`,
`docs/IE/IE_VAT_MVP_Design.md`) and aren't duplicated here — this doc is
scoped to GL's own core ledger domain, platform-wide, per the instruction's
framing ("your service" = GL's core Ledger/reporting slice, not every
jurisdiction-specific extension GL happens to carry). SOP/POP/IM/HR/EA each
answer this same question for their own domain in their own repo's docs.
