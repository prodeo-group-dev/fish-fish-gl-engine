package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.tax.TaxComputation
import com.theprodeogroup.fish.domain.tax.TaxComputationId
import com.theprodeogroup.fish.domain.tax.TaxComputationRepository
import com.theprodeogroup.fish.domain.tax.TaxRule
import com.theprodeogroup.fish.domain.tax.TaxRuleId
import com.theprodeogroup.fish.domain.tax.TaxRuleRepository
import com.theprodeogroup.fish.domain.tax.TaxType
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * In-memory stand-ins for the Tax repository interfaces, same discipline
 * as `LedgerRepositoryFakes.kt`/`TenancyRepositoryFakes.kt`.
 */
class FakeTaxRuleRepository : TaxRuleRepository {
    private val store = mutableMapOf<TaxRuleId, TaxRule>()
    override fun save(taxRule: TaxRule) { store[taxRule.id] = taxRule }
    override fun findById(id: TaxRuleId): TaxRule? = store[id]
    override fun findByJurisdictionAndTaxType(jurisdiction: String, taxType: TaxType): TaxRule? =
        store.values.find { it.jurisdiction == jurisdiction && it.taxType == taxType }
}

class FakeTaxComputationRepository : TaxComputationRepository {
    val saveCalls = mutableListOf<TaxComputationId>()
    private val store = mutableMapOf<TaxComputationId, TaxComputation>()
    override fun save(taxComputation: TaxComputation) {
        saveCalls.add(taxComputation.id)
        store[taxComputation.id] = taxComputation
    }
    override fun findById(id: TaxComputationId): TaxComputation? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<TaxComputation> =
        store.values.filter { it.companyId == companyId }
}
