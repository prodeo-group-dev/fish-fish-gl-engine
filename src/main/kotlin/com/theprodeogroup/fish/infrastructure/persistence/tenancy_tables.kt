package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definition for `Company` (originally
 * `V3__tenancy_tables.sql`, docs/DDD_Design.md Section 10.3) - matches
 * the migration's DDL exactly. Kept in `infrastructure`, not `domain`,
 * same split as `ledger_tables.kt`.
 *
 * `tenants`/`tenant_companies`/`tenant_admin_memberships`/`users`/
 * `memberships`/`membership_module_grants` are no longer read or written
 * by this codebase (`Tenant`/`User`/`Membership` moved to EA,
 * `docs/Tenancy_Administration_Extraction_DDD_Design.md`, 2026-09-06) -
 * their tables are deliberately left in place in Postgres, unreferenced,
 * rather than dropped: `companies.tenant_id` has a real FK to
 * `tenants(id)` (this file's own [CompaniesTable] comment), and this
 * codebase's own convention is to never delete persisted data, only stop
 * reading it. `database_migrator.kt`'s `ensureProdeoGroupTenantExistsBeforeV17`
 * callback still writes directly to `tenants` via raw SQL, independent
 * of any Exposed table object.
 */
object CompaniesTable : Table("companies") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val name = varchar("name", 255)
    val clientType = varchar("client_type", 20)
    val jurisdiction = varchar("jurisdiction", 100)
    val baseCurrency = varchar("base_currency", 3)
    val goingConcernStatus = varchar("going_concern_status", 20)
    val fiscalYearStartMonth = integer("fiscal_year_start_month")

    override val primaryKey = PrimaryKey(id)
}

/** V11 - see ModuleManagementPreference's own KDoc for what this does and doesn't mean. */
object CompanyModuleManagementPreferencesTable : Table("company_module_management_preferences") {
    val companyId = uuid("company_id")
    val module = varchar("module", 10)
    val selfManaged = bool("self_managed")
    val delegateName = varchar("delegate_name", 255).nullable()
    val delegateEmail = varchar("delegate_email", 255).nullable()

    override val primaryKey = PrimaryKey(companyId, module)
}
