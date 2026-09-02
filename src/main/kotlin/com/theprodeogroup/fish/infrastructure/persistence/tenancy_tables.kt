package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table definitions for the Tenancy schema
 * (`V3__tenancy_tables.sql`, docs/DDD_Design.md Section 10.3) - matches
 * the migration's DDL exactly. Kept in `infrastructure`, not `domain`,
 * same split as `ledger_tables.kt`.
 */
object TenantsTable : Table("tenants") {
    val id = uuid("id")
    val name = varchar("name", 255)
    val segment = varchar("segment", 20)
    val baseCurrency = varchar("base_currency", 3)
    val status = varchar("status", 20)
    val kybStatus = varchar("kyb_status", 20)
    val adminKycStatus = varchar("admin_kyc_status", 20)
    val kybVerificationDeadline = timestamp("kyb_verification_deadline").nullable()
    val adminPhoneNumber = varchar("admin_phone_number", 20).nullable()
    val adminPhoneVerificationStatus = varchar("admin_phone_verification_status", 20)
    val phoneVerificationDeadline = timestamp("phone_verification_deadline").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Persists `Tenant._companyIds` (docs/DDD_Design.md Section 9.2 step 3) -
 * deliberately its own join table, not derived from `companies.tenant_id`,
 * since Tenant tracks these IDs as its own explicit state (a Company only
 * counts as "under" a Tenant once `addCompany` is called).
 */
object TenantCompaniesTable : Table("tenant_companies") {
    val tenantId = uuid("tenant_id")
    val companyId = uuid("company_id")

    override val primaryKey = PrimaryKey(tenantId, companyId)
}

/** Persists `Tenant._adminMembershipIds` (Section 9.2 step 4) - same rationale as [TenantCompaniesTable]. */
object TenantAdminMembershipsTable : Table("tenant_admin_memberships") {
    val tenantId = uuid("tenant_id")
    val membershipId = uuid("membership_id")

    override val primaryKey = PrimaryKey(tenantId, membershipId)
}

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

object UsersTable : Table("users") {
    val id = uuid("id")
    val email = varchar("email", 255)
    val name = varchar("name", 255)

    override val primaryKey = PrimaryKey(id)
}

object MembershipsTable : Table("memberships") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val tenantId = uuid("tenant_id")
    val role = varchar("role", 30)
    val status = varchar("status", 20)
    /** V12 - see Membership.kt's own KDoc on why this is a real column, not derived from [role]. */
    val accessLevel = varchar("access_level", 10)

    override val primaryKey = PrimaryKey(id)
}

/** V15 - persists `Membership.grantedModules`, same one-to-many-child-table shape as [CompanyModuleManagementPreferencesTable]. */
object MembershipModuleGrantsTable : Table("membership_module_grants") {
    val membershipId = uuid("membership_id")
    val module = varchar("module", 10)

    override val primaryKey = PrimaryKey(membershipId, module)
}
