package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import java.util.Currency

/**
 * Application service for docs/DDD_Design.md Section 9.1's lighter
 * onboarding path - adding another legal entity to an *existing* Tenant
 * (e.g. Purse adding its Sierra Leone entity once UK is already
 * onboarded), as opposed to [OnboardTenantUseCase]'s heavier "onboard a
 * brand-new Tenant" flow. Deliberately does **not** create a new admin
 * `User`/`Membership` - `Membership` is Tenant-scoped, not Company-scoped
 * (Section 3.2), so every admin who already has access to the Tenant
 * automatically has access to any Company added under it. There's
 * nothing to inherit from `Tenant` beyond its identity (`Tenant` itself
 * carries no `ClientType`/jurisdiction/currency fields to default from) -
 * `Request` still needs the same jurisdiction-specific fields
 * [Company.create] always requires.
 *
 * **Returns `null`, not a `ValidationResult`, on failure** - matches this
 * codebase's own established idiom for "operation didn't apply"
 * throughout the domain layer (`PurchaseOrder.send()`,
 * `SalesOrder.deliverLine()`, `Customer.receivePayment()`, etc.), rather
 * than inventing a new Result-wrapping type for the `application` layer.
 * Covers two distinct failure reasons: [Request.tenantId] doesn't resolve
 * to an existing Tenant, or the Tenant exists but rejects the addition
 * (`Tenant.addCompany()` fails once the Tenant is `Closed`).
 *
 * **Only one `Tenant` save, not two** - unlike [OnboardTenantUseCase].
 * There, the Tenant didn't exist yet, so a bare first save was needed
 * before `Company` could reference it via `companies.tenant_id`. Here the
 * Tenant is already persisted (loaded via [TenantRepository.findById]),
 * so `Company` can reference it immediately - no ordering cycle to
 * resolve.
 *
 * **Validates before persisting anything**, to avoid ever saving an
 * orphaned `Company`: [Tenant.addCompany] is checked *before*
 * [CompanyRepository.save] runs, since `addCompany()` is a pure in-memory
 * check against `Tenant.status` with no side effect on failure. A Closed
 * Tenant therefore never results in a `Company` row existing with nothing
 * pointing at it.
 *
 * **Same no-atomicity policy as [OnboardTenantUseCase] (docs/DDD_Design.md
 * Section 10.5), and the same residual risk**: if `companyRepository.save()`
 * succeeds but the following `tenantRepository.save()` fails, the new
 * `Company` row exists (with a correct `tenant_id`) but isn't yet
 * reflected in `Tenant.companyIds` - `CompanyRepository.findAllByTenant()`
 * would find it, `TenantRepository.findById().companyIds` wouldn't, until
 * whatever retry mechanism eventually completes the second save.
 */
class AddCompanyToTenantUseCase(
    private val tenantRepository: TenantRepository,
    private val companyRepository: CompanyRepository
) {
    data class Request(
        val tenantId: TenantId,
        val companyName: String,
        val clientType: ClientType,
        val jurisdiction: String,
        val companyBaseCurrency: Currency
    )

    data class Result(
        val tenant: Tenant,
        val company: Company
    )

    fun execute(request: Request): Result? {
        val tenant = tenantRepository.findById(request.tenantId) ?: return null

        val company = Company.create(
            tenant.id, request.companyName, request.clientType, request.jurisdiction, request.companyBaseCurrency
        )

        val additionResult = tenant.addCompany(company.id)
        if (!additionResult.isValid) return null

        companyRepository.save(company)
        tenantRepository.save(tenant)

        return Result(tenant, company)
    }
}
