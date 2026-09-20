package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * Persistence contracts for the Tax context (docs/DDD_Design.md Section
 * 10.11) - same interface-in-domain/implementation-in-infrastructure
 * split as every other repository in this codebase.
 */
interface TaxRuleRepository {
    fun save(taxRule: TaxRule)
    fun findById(id: TaxRuleId): TaxRule?

    /**
     * The natural lookup for "what's the current rate for this
     * jurisdiction and tax type" - jurisdiction+taxType is treated as
     * the effective business key here, since no effective-dating/rate-
     * history model exists yet (see `TaxRule`'s own KDoc). Returns
     * `null`, not a list, on the working assumption that only one
     * TaxRule is configured per jurisdiction/type combination at a
     * time - if that assumption breaks (multiple candidate rows),
     * callers should expect the implementation to surface that as an
     * error rather than silently picking one.
     */
    fun findByJurisdictionAndTaxType(jurisdiction: Jurisdiction, taxType: TaxType): TaxRule?
}

interface TaxComputationRepository {
    fun save(taxComputation: TaxComputation)
    fun findById(id: TaxComputationId): TaxComputation?
    fun findAllByCompany(companyId: CompanyId): List<TaxComputation>
}

interface VatReturnRepository {
    fun save(vatReturn: VatReturn)
    fun findById(id: VatReturnId): VatReturn?
    fun findAllByCompany(companyId: CompanyId): List<VatReturn>
}
