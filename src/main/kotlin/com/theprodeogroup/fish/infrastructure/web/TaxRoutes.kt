package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeTaxResult
import com.theprodeogroup.fish.application.ComputeTaxUseCase
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tax.TaxComputationInputs
import com.theprodeogroup.fish.domain.tax.TaxComputationRepository
import com.theprodeogroup.fish.domain.tax.TaxRuleRepository
import com.theprodeogroup.fish.domain.tax.TaxType
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID

/**
 * `POST`/`GET /companies/{companyId}/tax` - closes the parity audit's
 * "wire or shelve tax computation" gap (2026-08-30): `ComputeTaxUseCase`
 * existed, tested, and completely unreachable. Now Tax's own route,
 * gated by [ManagedModule.TAX] specifically (2026-08-31, "Tax
 * management should be its own module") via [authorizeTenantForModule] -
 * the first route in this codebase to actually enforce
 * `Membership.grantedModules`, not just [AccessLevel].
 *
 * Only [com.theprodeogroup.fish.domain.tax.TaxType.CORPORATE_INCOME_TAX]
 * is supported - the only type [ComputeTaxUseCase] itself can compute
 * (see that use case's own KDoc). The [TaxRule] to apply is resolved
 * from the Company's own `jurisdiction`, not caller-supplied - a
 * caller has no business picking which jurisdiction's rate applies to
 * their own books.
 *
 * `POST` (not `GET`) for computing - `ComputeTaxUseCase` persists a new
 * `TaxComputation` row on every call (an audit record, not a cached
 * report), which is a resource-creating action, not an idempotent read.
 */
fun Route.taxRoutes(
    computeTaxUseCase: ComputeTaxUseCase,
    companyRepository: CompanyRepository,
    taxRuleRepository: TaxRuleRepository,
    taxComputationRepository: TaxComputationRepository
) {
    post("/companies/{companyId}/tax") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@post
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@post
        val companyId = CompanyId(companyUuid)

        val company = companyRepository.findById(companyId)
        if (company == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            return@post
        }
        if (!call.verifyClaimedTenant(company.tenantId)) return@post
        call.authorizeTenantForModule(company.tenantId, company.id, ManagedModule.TAX, AccessLevel.WRITE) ?: return@post

        val taxRule = taxRuleRepository.findByJurisdictionAndTaxType(company.jurisdiction, TaxType.CORPORATE_INCOME_TAX)
        if (taxRule == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponseDto("no_tax_rule", "No corporate income tax rule is configured for jurisdiction '${company.jurisdiction}'")
            )
            return@post
        }

        val request = call.receive<ComputeTaxRequestDto>()
        val periodUuid = try {
            UUID.fromString(request.periodId)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.periodId}' is not a valid periodId"))
            return@post
        }
        val turnover = request.turnover?.let {
            it.toBigDecimalOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'$it' is not a valid turnover"))
                return@post
            }
        }
        val fixedAssets = request.fixedAssets?.let {
            it.toBigDecimalOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'$it' is not a valid fixedAssets"))
                return@post
            }
        }

        val result = computeTaxUseCase.execute(
            ComputeTaxUseCase.Request(
                companyId = companyId,
                periodId = PeriodId(periodUuid),
                taxRule = taxRule,
                currency = company.baseCurrency,
                inputs = TaxComputationInputs(category = request.category, turnover = turnover, fixedAssets = fixedAssets)
            )
        )

        when (result) {
            is ComputeTaxResult.Success -> call.respond(HttpStatusCode.Created, result.computation.toDto())
            ComputeTaxResult.PeriodNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found", "Period not found"))
            ComputeTaxResult.PeriodBelongsToDifferentCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_mismatch", "That Period does not belong to this Company"))
            ComputeTaxResult.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
        }
    }

    get("/companies/{companyId}/tax") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)

        val company = companyRepository.findById(companyId)
        if (company == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            return@get
        }
        if (!call.verifyClaimedTenant(company.tenantId)) return@get
        call.authorizeTenantForModule(company.tenantId, company.id, ManagedModule.TAX, AccessLevel.READ) ?: return@get

        val computations = taxComputationRepository.findAllByCompany(companyId).map { it.toDto() }
        call.respond(HttpStatusCode.OK, computations)
    }
}

private fun com.theprodeogroup.fish.domain.tax.TaxComputation.toDto() = TaxComputationDto(
    id = id.value.toString(),
    companyId = companyId.value.toString(),
    periodId = periodId.value.toString(),
    taxRuleId = taxRuleId.value.toString(),
    taxableProfit = taxableProfit.amount.toPlainString(),
    taxDue = taxDue.amount.toPlainString(),
    currency = taxDue.currency.currencyCode,
    computedAt = computedAt.toString()
)
