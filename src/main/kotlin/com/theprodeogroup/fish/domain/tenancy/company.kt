package com.theprodeogroup.fish.domain.tenancy

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.common.ValidationResult
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
 *
 * [fiscalYearStartMonth] (2026-09-02, "the fiscal year has to be set
 * during Tenant onboarding") - the calendar month (1=January...12=December)
 * this Company's financial year begins in, e.g. 4 for a UK-style
 * April-March year, 1 for a plain calendar year. Defaults to January
 * here at the domain-constructor level so the many existing tests that
 * just need "a Company" and don't care about fiscal timing aren't
 * forced to supply one; [com.theprodeogroup.fish.application.OnboardTenantUseCase.Request]/
 * [com.theprodeogroup.fish.application.AddCompanyToTenantUseCase.Request]
 * have no such default - a real onboarding call must supply it
 * explicitly, matching the user's own framing.  Stored only, not yet
 * load-bearing for anything - [Period] creation/[openingPeriod] still
 * defaults to a plain calendar month starting "today," not derived
 * from this field. Deliberately deferred: driving Period generation
 * off a fiscal year is a separate, bigger feature this doesn't build.
 */
class Company private constructor(
    val id: CompanyId,
    val tenantId: TenantId,
    val name: String,
    val clientType: ClientType,
    val jurisdiction: String,
    val baseCurrency: Currency,
    val fiscalYearStartMonth: Int,
    val moduleManagementPreferences: List<ModuleManagementPreference> = emptyList()
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
            id: CompanyId = CompanyId.generate(),
            fiscalYearStartMonth: Int = 1,
            moduleManagementPreferences: List<ModuleManagementPreference> = emptyList()
        ): Company {
            require(fiscalYearStartMonth in 1..12) {
                "fiscalYearStartMonth must be between 1 (January) and 12 (December): $fiscalYearStartMonth"
            }
            return Company(id, tenantId, name, clientType, jurisdiction, baseCurrency, fiscalYearStartMonth, moduleManagementPreferences)
        }

        /**
         * Rebuilds an already-valid Company from persisted state (Section 10) -
         * `internal`, matches the repository-only visibility of every other
         * aggregate's `reconstitute()`.
         */
        internal fun reconstitute(
            id: CompanyId,
            tenantId: TenantId,
            name: String,
            clientType: ClientType,
            jurisdiction: String,
            baseCurrency: Currency,
            fiscalYearStartMonth: Int,
            goingConcernStatus: GoingConcernStatus,
            moduleManagementPreferences: List<ModuleManagementPreference> = emptyList()
        ): Company {
            val company = Company(id, tenantId, name, clientType, jurisdiction, baseCurrency, fiscalYearStartMonth, moduleManagementPreferences)
            company.goingConcernStatus = goingConcernStatus
            return company
        }
    }
}
