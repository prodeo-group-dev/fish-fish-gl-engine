# GL — the FiSH GL Engine

The core General Ledger as a Service (GLaaS) platform underlying Purse,
Scrip, Osusu, and BuzzMe, and offered standalone as a B2B ledger product —
see the top-level `FiSH/CLAUDE.md` for the full business context. This repo
is the system of record for every financial posting on the platform; POP,
SOP, IM, HR, and EA are all separate repos that call into it (or, for EA,
that it calls out to) rather than posting directly.

**Status: live in production**, deployed at `capital.theprodeogroup.com`.
Kotlin/Gradle/Ktor (Kotlin 2.2, Gradle 8.10, JDK 21), PostgreSQL via Exposed
+ HikariCP, Flyway-migrated (24 migrations). 645 tests.

## What's built

- **Ledger** (`domain/ledger`) — the core domain: `Account`, `Period`,
  `JournalEntry`/`JournalLine`, `Money`, posting/period-close use cases,
  and the reporting suite (`ReportsRoutes`: Balance Sheet, Profit & Loss,
  Cash Flow, Working Capital, Bank Reconciliation).
- **Tenancy** (`domain/tenancy`) — `Tenant`/`Company`/`User`/`Membership`
  originated here; the live source of truth has since moved to `EA`
  (`FiSH/docs/Tenancy_Administration_Extraction_DDD_Design.md`) — GL's own
  copies are being narrowed to what's still load-bearing for the Ledger
  (`Company`), not kept as a second live administration surface.
- **Tax** (`domain/tax`) — Corporate Income Tax computation
  (`ComputeTaxUseCase`), per-jurisdiction `TaxRule` as global reference data.
- **Fixed Assets** (`domain/fixedassets`) — register, straight-line
  depreciation, disposal, IAS 36 impairment.
- **Payroll** (`domain/payroll`) — `PayRun`, `LeaveAccrual` (IAS 19),
  the posting interface `HR` calls.
- **Purchasing/Sales/Inventory** (`domain/purchasing`, `domain/sales`,
  `domain/inventory`) — the pre-extraction implementations
  (`PurchaseOrder`, `SalesOrder`, `StockItem`) still relied on internally,
  alongside the newer thin posting interfaces (`RecordVendorObligation*`,
  `RecordSaleAndCollection*`, `RecordInventoryReceiptAndIssue*`) that POP/
  SOP/IM call instead of posting detail directly — see
  `FiSH/docs/Ecosystem_Extraction_DDD_Design.md` for which is which and why
  both exist side by side.
- **Lending** (`domain/lending`) — `ArrearsCase`, Purse's arrears workflow;
  flagged for relocation out of GL, not yet moved.
- 39 application-layer use cases, 22 web route files.

## Relationship to POP/SOP/IM/HR/EA

- GL remains the sole system of record for financial postings — every
  sibling computes its own domain amounts and calls a thin GL posting
  interface, never posts ledger detail itself.
- GL is itself an EA caller for tenant/membership checks (`authorizeTenant`)
  and, since `d376f66`, an EA-recognized *service account* for its own
  calls into POP/SOP/IM/HR-adjacent flows — service-to-service calls
  bypass the EA membership check by design
  (`FiSH/docs/Service_Account_Identity_And_EA_Membership_Design_Note.md`).
- No database-level tenant isolation (RLS) yet — deliberately held, see
  `FiSH/docs/Database_Tenant_Isolation_RLS_Scope.md`.

## Running locally

```bash
./gradlew test
./gradlew run
```

`./gradlew test` needs nothing beyond the JDK. `./gradlew run` needs a real
Postgres reachable via `FISH_DB_HOST`/`FISH_DB_PORT`/`FISH_DB_NAME` (default
`localhost`/`5432`/`fish_dev`) and `FISH_DB_USER`/`FISH_DB_PASSWORD` (no
default — throws if missing), plus a JWT verifier
(`FISH_JWT_ISSUER`/`FISH_JWT_AUDIENCE`/`FISH_JWT_JWKS_URL`) and the
service-account audiences (`FISH_JWT_SERVICE_AUDIENCE`/`_POP`/`_IM`/`_HR`)
GL trusts for inbound sibling calls.

## Infra

`infra/terraform/` — self-hosted Jenkins CI/CD (`Jenkinsfile`: test →
integration-test + docker-build in parallel → deploy to ECS on `master`),
ECS/Fargate, RDS Postgres (shared with EA/POP/SOP/IM/HR, one logical
database per service), CloudFront + S3 for the `WEB` frontend, Cognito for
identity.
