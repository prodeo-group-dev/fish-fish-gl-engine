package com.theprodeogroup.fish.domain.tenancy

/**
 * Persistence contract for `Company` (docs/DDD_Design.md Section 10.3) -
 * interface lives in `domain`, the Exposed-backed implementation lives in
 * `infrastructure.persistence`, matching the split already established
 * for the core Ledger repositories (Section 10.2). Deliberately no
 * `delete()`, same rationale as the Ledger repositories - nothing in
 * this codebase deletes a Company, only deactivates/closes it.
 *
 * `Tenant`/`User`/`Membership` moved to EA
 * (`docs/Tenancy_Administration_Extraction_DDD_Design.md`, 2026-09-06) -
 * `Company` is the one Tenancy aggregate that stays in GL, load-bearing
 * for Ledger scoping (`Account`/`Period`/`JournalEntry` are all
 * `companyId`-scoped).
 */
interface CompanyRepository {
    fun save(company: Company)
    fun findById(id: CompanyId): Company?
    fun findAllByTenant(tenantId: TenantId): List<Company>
}
