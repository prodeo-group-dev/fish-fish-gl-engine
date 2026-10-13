# GL Engine — Minimum Viable Development Level

**Status: refreshed 2026-10-13** (first pass 2026-10-02), per the platform-wide
instruction (via CM) to determine the smallest real, working slice of each
service's domain that could actually go live, not full backlog completion.
Checked directly against the code on `master` `ccddf1b` (live as GL :126),
the declared routes and the migrations through V35, not from memory or from
the backlog's own framing. Same shape as `HR/docs/HR_MVP_Definition.md`: the
floor, then what a Company can route around, then real pre-go-live flags
distinct from backlog priority.

## What changed since the 2026-10-02 version

The tester's floors review (`docs/reports/Playbooks/TestPlans/MVP_Floors_SOP_IM_POP_GL.md`)
and a re-read of the code found these corrections. They are stated first so
nobody relies on the old text.

| # | Old statement | Now |
|---|---|---|
| 1 | "A bookkeeper could close a month on GL using only the pieces above." | **Not true over HTTP.** There is no route to create, open, close or reopen a Period, and no generic reversal route (see "The floor is not fully met" below). The old text counted domain methods that no route reaches. |
| 2 | "VAT: not built anywhere in the platform." | **Wrong since 2026-09-19.** VAT is live: `vat_rates` (V30), `GET /companies/{id}/vat-categories`, a VAT category on every sale and purchase line (`record-sale`, `record-obligation`), the VAT control account 2150 in every business chart, and `POST /companies/{id}/vat-return`. Rates are **verified for the United Kingdom (three bands) and Ireland (five bands) only**; Sierra Leone's 15% GST is loaded but was not verified as of the 2026-10-07 operator sheet (GL refuses rather than guess; re-check before relying on this); Liberia, Guinea, Côte d'Ivoire and Nigeria have no rows. `TaxType` still has only `CORPORATE_INCOME_TAX`; VAT has its own computation path. |
| 3 | Cash and bank books were not in the floor. | **In the floor now** (live since GL :119): one book per cash or bank account, receipts, payments, transfers and undo, account 1000 always the Cash Book, bank accounts as further books, the standard-accounts route for older charts, same-day order (V35). See "The floor". |
| 4 | "Raw Trial Balance as its own report: no dedicated route." | **Route exists:** `GET /companies/{id}/reports/trial-balance`. Also live since: trading profit and loss, working capital, fixed-asset register, and date-range profit and loss and cash flow. |
| 5 | Currency not stated. | **SLE is the only currency onboarded across the Mano River operation** (standing decision 2026-08-22; LRD, GNF and XOF are reference only). Since V34 a Company's currency is **derived from its jurisdiction** at creation (GBP, EUR, NGN, SLE). One currency per Company; foreign-currency accounts are designed (`docs/GL_Foreign_Currency_Accounts_IAS21_Design.md`), not built. |
| 6 | Idempotency not mentioned. | A refused request does **not** spend an Idempotency-Key (only a 2xx answer is stored, GL :124); a key reused with a different body is 422. The simultaneous-duplicate gap is open (`docs/GL_Idempotency_Claim_First_Design_Note.md`). |
| 7 | "Bank Reconciliation: can be done manually outside the system." | Built and in the floor: start, match, unmatch, **complete, cancel**, the balance tie-out, and a picker that offers only POSTED entries on that account not already cleared by a completed reconciliation. |
| 8 | Access control: per-Company authorization only. | Also in the floor: the **service allow-list is enforcing** (each service login may call only its own GL endpoints), the tenancy sequence on every Company route, strict decoding of EA's `/me`. **Finer role rules (RBAC) are deliberately after features:** frozen except add-a-deny fixes until the RBAC SPUTO lands. |
| 9 | Go-live flag 3 read "no documented backup/DR story". | Partly wrong: automated RDS backups exist; what is missing is a recovery story. See flag 3 below. |
| 10 | Three flags. | Five flags now: two correctness flags are added (flags 4 and 5). |

## The floor, as it stands

What a Company can do on GL **today, over the API and the screens that call it**, with nothing hand-waved:

- **Chart of Accounts.** `POST /companies/{id}/accounts`, typed `AccountType`/`AccountClassification`, a starter chart by business type seeded at Company creation, `POST /companies/{id}/standard-accounts` to bring an older Company's chart up to the current template (adds only what is missing, reports a conflict if a code has a different type, never changes an account).
- **Manual journal entries.** `POST /journal-entries` enforces the balanced-entry invariant, per currency; posted entries are immutable (see the floor gap on correction).
- **Opening balances.** `POST /companies/{id}/accounts/{id}/opening-balance`, and the CSV imports for general-ledger balances and for fixed assets (validate, then import). Receivable, payable and stock opening figures are not loadable through GL (SOP, POP and IM own them).
- **Cash and bank books (books of original entry).** Cash and bank accounts as ledger accounts of kind CASH or BANK; per-account book with opening balance, running balance and same-day order; money in, money out and moves between books recorded in the book; Undo of an entry that started in a book; accounts owned by another module refused as counter-accounts; one-currency-per-Company seeding from the jurisdiction.
- **Bank reconciliation** on bank accounts, as above.
- **Financial statements.** Balance sheet, profit and loss (date range supported), cash flow (date range supported), trial balance, trading profit and loss, working capital, fixed-asset register, all derived from posted entries.
- **VAT** for the United Kingdom and Ireland: category per line, rate as of the transaction date, the VAT control account, the return, the category breakdown.
- **Integration postings** from SOP, POP, IM and HR (sale, collection, sales return, supplier obligation and payment, inventory receipt and issue, pay run, leave accruals), each with Idempotency-Key support, behind the service allow-list.
- **Dashboard reads** (live :126): `cash-balance-series` and `receivables-payables-summary`, plus money velocity, expense velocity and sales-to-expense ratio.
- **Multi-tenancy and authorization.** Tenant resolved from the Company, `X-Tenant-Id` verified, access checked against EA (`/me`, fail-closed 503), per-Company read or write level, strict decoding.

## The floor is not fully met (said plainly)

Two things a real bookkeeper needs are **not reachable over HTTP today**, so the old "floor already crossed" claim is withdrawn until they exist:

1. **No Period lifecycle.** A Period is created in exactly one place, `AddCompanyToTenantUseCase`, once per Company (one month-long Period, opened immediately); no route creates, opens, closes, reopens or locks a Period, and `ClosePeriodUseCase` is referenced by no route. No posting use case compares an entry date with its Period's dates. So a Company has **one Period holding its whole history**, there is no month-end close, no "as at" balance sheet, and "open Period" reports mean "since inception". Opening balances dated outside the first month are refused. Decision (Femi, relayed 2026-10-10): automatic opening of the next month, **explicit closing**, designed in `docs/GL_Period_Management_Scoping.md`, deferred, nothing built.
2. **No generic reversal route.** `ReverseJournalEntryUseCase` exists but no route reaches it. The only correction path over HTTP is **Undo of an entry that started in a cash or bank book** (atomic reversal). A posted sale, collection, supplier payment, pay run or manual entry cannot be reversed by a caller; a correction is a new entry. (Reversals that exist are worded in words: "Reversal of <description> (<date>)".)

## What is not built but can be routed around manually

Automation or convenience gaps a Company can work around at low volume, not hard blockers to a first go-live:

- **Corporate Income Tax computation** (`ComputeTaxUseCase`, from the jurisdiction's seeded rule; the only `TaxType`): an accountant can compute it externally and post the entry.
- **VAT outside the United Kingdom and Ireland**: the Company's own bookkeeper handles it until the rates are verified.
- **Departmental, branch and group accounts**: tag-only or unbuilt; not required for a single-Company MVP.
- **Foreign-currency accounts** (IAS 21): designed, not built.
- **Opening Figures for receivables, payables and stock** through GL: owned by SOP, POP and IM.
- **Bank feeds / Open Banking**: not built (statement lines are entered).
- **Per-month reporting and year-end closing into retained earnings**: follows the Periods work above.

## Known accounting-correctness gaps (they affect real figures once a Company uses the integrations)

These are not infrastructure; they are wrong or missing postings, found and measured, with designs awaiting approval.

- **Seam A, the stock purchase double count** (measured live 2026-10-12 on PO-000001): an IM receipt credits Accounts Payable and POP's three-way match credits it again and debits an expense, so AP is overstated and the cost is in inventory and expense. Fix designed (GRNI), `docs/POP_IM_GL_Purchase_Posting_Design_GL_Half.md`; awaiting Femi.
- **Sales returns and credit notes**: `record-sales-return` posts one amount with no VAT reversal, there is no Sales Returns account, and `VatReturn` would read a reversal as input VAT. Fix designed, including Discounts Given and Discounts Received (Femi, 2026-10-13), `docs/Sales_Returns_Credit_Notes_GL_Half.md`; awaiting Femi.
- **Fixed assets**: depreciation has no per-year guard (two keys post two annual charges), module-owned accounts (1200, 1210) accept manual journals, acquisition dates are not checked against a Period, and the register is not tied to the ledger per asset. Designed in `docs/Fixed_Assets_Security_Playbook.md`; awaiting Femi (the cross-Company Period check is already closed, T19).
- **Aggregate routes are not atomic**: create sales invoice, fixed-asset actions and leave-accrual actions are several separate writes, so an exception part-way can leave partial state that a keyed retry repeats; two simultaneous requests with the same key can both post. Designed (`docs/GL_Idempotency_Claim_First_Design_Note.md`, option C proven by a spike); awaiting Femi.

## Real pre-go-live flags, not backlog nice-to-haves

These cannot simply be routed around once this switches from Development to Live. Flagged for Femi's confirmation, not asserted as blocking.

1. **No audit trail of who did what, when (confirmed 2026-10-13).** The store exists (V27 `audit_log_entries`, an Exposed repository, tested) and **nothing writes to it**: no use case or route creates an `AuditLogEntry`, and a journal entry carries no actor, only its source (manual, integration, reversal, cash book). Posted entries are immutable and corrected only by reversal, a real integrity property, but not an answer to "who posted this". First writers are task FS-3 of the Fixed Assets Playbook (an interim shape) and the platform audit design (T17); the trust and governance case is unchanged.
2. **No database-level tenant isolation (RLS).** Deliberately not built (platform note `docs/Database_Tenant_Isolation_RLS_Scope.md`, recommendation: hold). Scoped for GL in `docs/GL_RLS_Scoping.md` (38 tables, about 11 to 12.5 days plus the database role split). The strongest argument for deciding now: while production data is legacy test data the migrations, backfill and soak cost no customer risk; after the Live switch each is a change to a live ledger.
3. **Backups exist; a recovery story does not.** Verified in `Infrastructure/rds.tf` and `variables.tf`: automated RDS backups are on, but retention defaults to **1 day** (the free-tier cap noted in the variable), the instance is **single-AZ**, `skip_final_snapshot = true` and `deletion_protection = false` (the file's own comment says to flip both when real data exists), and no restore runbook or restore test is documented. For a ledger, "we can restore it, we have done it, and we know how long it takes" is the missing piece. (The live retention value should be read from AWS; the code default is 1.)
4. **The accounting-correctness gaps above** (seam A and sales returns first): once real Companies use SOP, POP and IM against GL, these overstate payables, mis-state VAT and mis-state revenue. They are flags for Femi because the fix is a posting design, not hardening.
5. **No month-end close** (floor gap 1). A real accountant cannot lock a month; every posting stays open for ever.

## What this means for sequencing

Nothing here changes `docs/GL_POP_IM_SOP_Backlog.md`'s priorities. It changes the framing: flags 1 to 5 are worth treating as "confirm with Femi before calling a real go-live", and the floor is "met except Periods and reversal", not "met". RBAC enforcement (who may do what at finer grain) is deliberately after features, per the RBAC SPUTO, and does not move the floor.

## Not addressed here

Jurisdiction-specific go-live criteria are their own documents (`docs/IE/IE_MVP_Definition.md`, `docs/IE/IE_VAT_MVP_Design.md`, `docs/UK/NI_Jurisdiction_Specification.md`). SOP, POP, IM, HR and EA each answer this question for their own domain in their own repo. The operator sheet (`docs/GL_Playbook_Draft.md`) is the support-facing view of the same facts.
