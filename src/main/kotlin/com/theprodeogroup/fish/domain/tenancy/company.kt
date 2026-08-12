package com.theprodeogroup.fish.domain.tenancy

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.ValidationResult
import java.util.Currency

/**
 * One legal entity within a Tenant, with its own Chart of Accounts and
 * books (docs/DDD_Design.md Section 3.2). A Tenant may hold several
 * Companies (group structure) - e.g. Purse's UK/NI/ROI/SL entities each
 * get their own Company, own COA, own currency, under one Tenant.
 *
 * Scope note (2026-08-11): this is deliberately the core build only -
 * name, ClientType, jurisdiction, currency, going-concern status. Company
 * relationships (parent/consolidation) are confirmed design (Section 3.2)
 * but not built here - not load-bearing for this aggregate to work, per
 * the agreed minimal-build scope.
 */
class Company private constructor(
    val id: CompanyId,
    val tenantId: TenantId,
    val name: String,
    val clientType: ClientType,
    val jurisdiction: String,
    val baseCurrency: Currency
) {
    /**
     * Defaults to ASSUMED - the standard accounting default, not a special
     * case, per Section 9.7. Moves to SUBSTANTIAL_DOUBT via
     * [flagSubstantialDoubt], which the (not yet built) Lending context
     * calls when a business borrower's ArrearsCase reaches Final
     * Resolution.
     */
    var goingConcernStatus: GoingConcernStatus = GoingConcernStatus.ASSUMED
        private set

    fun flagSubstantialDoubt(): ValidationResult {
        if (!goingConcernStatus.canTransitionTo(GoingConcernStatus.SUBSTANTIAL_DOUBT)) {
            return ValidationResult.failure(
                "Cannot flag substantial doubt from status $goingConcernStatus"
            )
        }
        goingConcernStatus = GoingConcernStatus.SUBSTANTIAL_DOUBT
        return ValidationResult.success()
    }

    companion object {
        /** Section 9.2 step 3: create the first (or a subsequent) Company under a Tenant. */
        fun create(
            tenantId: TenantId,
            name: String,
            clientType: ClientType,
            jurisdiction: String,
            baseCurrency: Currency,
            id: CompanyId = CompanyId.generate()
        ): Company = Company(id, tenantId, name, clientType, jurisdiction, baseCurrency)
    }
}
