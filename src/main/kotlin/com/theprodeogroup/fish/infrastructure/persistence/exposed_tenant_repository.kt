package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import com.theprodeogroup.fish.domain.tenancy.VerificationStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/**
 * Exposed-backed `TenantRepository` (docs/DDD_Design.md Section 10.3).
 * [save] also reconciles `tenant_companies`/`tenant_admin_memberships` -
 * delete-and-reinsert on every call, same precedent as
 * `ExposedJournalEntryRepository`'s `journal_lines` handling, since these
 * ID sets are small and only grow one at a time via `addCompany`/
 * `addAdminMembership`.
 *
 * Caller responsibility: a Company/Membership referenced by
 * `tenant.companyIds`/`adminMembershipIds` must already be saved (its own
 * repository's `save()` called first) before this `save()` runs, since
 * the join tables carry real foreign keys to `companies`/`memberships`.
 */
class ExposedTenantRepository : TenantRepository {

    override fun save(tenant: Tenant): Unit = transaction {
        val exists = TenantsTable.selectAll().where { TenantsTable.id eq tenant.id.value }.count() > 0
        if (exists) {
            TenantsTable.update({ TenantsTable.id eq tenant.id.value }) { statement ->
                populate(statement, tenant)
            }
        } else {
            TenantsTable.insert { statement ->
                statement[id] = tenant.id.value
                populate(statement, tenant)
            }
        }

        TenantCompaniesTable.deleteWhere { TenantCompaniesTable.tenantId eq tenant.id.value }
        tenant.companyIds.forEach { companyId ->
            TenantCompaniesTable.insert { statement ->
                statement[tenantId] = tenant.id.value
                statement[this.companyId] = companyId.value
            }
        }

        TenantAdminMembershipsTable.deleteWhere { TenantAdminMembershipsTable.tenantId eq tenant.id.value }
        tenant.adminMembershipIds.forEach { membershipId ->
            TenantAdminMembershipsTable.insert { statement ->
                statement[tenantId] = tenant.id.value
                statement[this.membershipId] = membershipId.value
            }
        }
        Unit
    }

    override fun findById(id: TenantId): Tenant? = transaction {
        TenantsTable.selectAll().where { TenantsTable.id eq id.value }
            .singleOrNull()
            ?.toTenant(id)
    }

    override fun findAllActive(): List<Tenant> = transaction {
        TenantsTable.selectAll().where { TenantsTable.status eq TenantStatus.ACTIVE.name }
            .map { row -> row.toTenant(TenantId(row[TenantsTable.id])) }
    }

    private fun loadCompanyIds(tenantId: TenantId): Set<CompanyId> =
        TenantCompaniesTable.selectAll().where { TenantCompaniesTable.tenantId eq tenantId.value }
            .map { CompanyId(it[TenantCompaniesTable.companyId]) }
            .toSet()

    private fun loadAdminMembershipIds(tenantId: TenantId): Set<MembershipId> =
        TenantAdminMembershipsTable.selectAll().where { TenantAdminMembershipsTable.tenantId eq tenantId.value }
            .map { MembershipId(it[TenantAdminMembershipsTable.membershipId]) }
            .toSet()

    private fun populate(statement: UpdateBuilder<*>, tenant: Tenant) {
        statement[TenantsTable.name] = tenant.name
        statement[TenantsTable.segment] = tenant.segment.name
        statement[TenantsTable.baseCurrency] = tenant.baseCurrency.currencyCode
        statement[TenantsTable.status] = tenant.status.name
        statement[TenantsTable.kybStatus] = tenant.kybStatus.name
        statement[TenantsTable.adminKycStatus] = tenant.adminKycStatus.name
        statement[TenantsTable.kybVerificationDeadline] = tenant.kybVerificationDeadline
    }

    private fun ResultRow.toTenant(id: TenantId): Tenant = Tenant.reconstitute(
        id = id,
        name = this[TenantsTable.name],
        segment = TenantSegment.valueOf(this[TenantsTable.segment]),
        baseCurrency = Currency.getInstance(this[TenantsTable.baseCurrency]),
        status = TenantStatus.valueOf(this[TenantsTable.status]),
        kybStatus = VerificationStatus.valueOf(this[TenantsTable.kybStatus]),
        adminKycStatus = VerificationStatus.valueOf(this[TenantsTable.adminKycStatus]),
        kybVerificationDeadline = this[TenantsTable.kybVerificationDeadline],
        companyIds = loadCompanyIds(id),
        adminMembershipIds = loadAdminMembershipIds(id)
    )
}
