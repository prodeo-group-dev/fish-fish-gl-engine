package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.GoingConcernStatus
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.ModuleManagementPreference
import com.theprodeogroup.fish.domain.tenancy.TenantId
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
 * Exposed-backed `CompanyRepository` (docs/DDD_Design.md Section 10.3).
 * `companies.tenant_id` has a real FK to `tenants(id)` (unlike the Ledger
 * tables' unconstrained `company_id`) - Tenant is persisted in this same
 * PR, so there's no forward-reference gap to work around here.
 */
class ExposedCompanyRepository : CompanyRepository {

    override fun save(company: Company): Unit = transaction {
        val exists = CompaniesTable.selectAll().where { CompaniesTable.id eq company.id.value }.count() > 0
        if (exists) {
            CompaniesTable.update({ CompaniesTable.id eq company.id.value }) { statement ->
                populate(statement, company)
            }
        } else {
            CompaniesTable.insert { statement ->
                statement[id] = company.id.value
                populate(statement, company)
            }
        }

        // Delete-then-reinsert - at most 5 rows (one per ManagedModule),
        // never worth a diffing dance.
        CompanyModuleManagementPreferencesTable.deleteWhere { CompanyModuleManagementPreferencesTable.companyId eq company.id.value }
        company.moduleManagementPreferences.forEach { preference ->
            CompanyModuleManagementPreferencesTable.insert { statement ->
                statement[companyId] = company.id.value
                statement[module] = preference.module.name
                statement[selfManaged] = preference.selfManaged
                statement[delegateName] = preference.delegateName
                statement[delegateEmail] = preference.delegateEmail
            }
        }
        Unit
    }

    override fun findById(id: CompanyId): Company? = transaction {
        CompaniesTable.selectAll().where { CompaniesTable.id eq id.value }
            .map { it.toCompany(loadModuleManagementPreferences(id)) }
            .singleOrNull()
    }

    override fun findAllByTenant(tenantId: TenantId): List<Company> = transaction {
        CompaniesTable.selectAll().where { CompaniesTable.tenantId eq tenantId.value }
            .map { it.toCompany(loadModuleManagementPreferences(CompanyId(it[CompaniesTable.id]))) }
    }

    private fun loadModuleManagementPreferences(companyId: CompanyId): List<ModuleManagementPreference> =
        CompanyModuleManagementPreferencesTable.selectAll().where { CompanyModuleManagementPreferencesTable.companyId eq companyId.value }
            .map { row ->
                val module = ManagedModule.valueOf(row[CompanyModuleManagementPreferencesTable.module])
                if (row[CompanyModuleManagementPreferencesTable.selfManaged]) {
                    ModuleManagementPreference.selfManaged(module)
                } else {
                    ModuleManagementPreference.delegatedTo(
                        module,
                        row[CompanyModuleManagementPreferencesTable.delegateName]!!,
                        row[CompanyModuleManagementPreferencesTable.delegateEmail]!!
                    )
                }
            }

    private fun populate(statement: UpdateBuilder<*>, company: Company) {
        statement[CompaniesTable.tenantId] = company.tenantId.value
        statement[CompaniesTable.name] = company.name
        statement[CompaniesTable.clientType] = company.clientType.name
        statement[CompaniesTable.jurisdiction] = company.jurisdiction
        statement[CompaniesTable.baseCurrency] = company.baseCurrency.currencyCode
        statement[CompaniesTable.goingConcernStatus] = company.goingConcernStatus.name
    }

    private fun ResultRow.toCompany(moduleManagementPreferences: List<ModuleManagementPreference>): Company = Company.reconstitute(
        id = CompanyId(this[CompaniesTable.id]),
        tenantId = TenantId(this[CompaniesTable.tenantId]),
        name = this[CompaniesTable.name],
        clientType = ClientType.valueOf(this[CompaniesTable.clientType]),
        jurisdiction = this[CompaniesTable.jurisdiction],
        baseCurrency = Currency.getInstance(this[CompaniesTable.baseCurrency]),
        goingConcernStatus = GoingConcernStatus.valueOf(this[CompaniesTable.goingConcernStatus]),
        moduleManagementPreferences = moduleManagementPreferences
    )
}
