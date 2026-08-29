package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeMoneyVelocityUseCase
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/money-velocity` - the dashboard KPI's
 * data source (see `ComputeMoneyVelocityUseCase`'s own KDoc). This
 * codebase's first read-only, Company-scoped route - uses
 * [authorizeTenantForRead], not [authorizeTenantForWrite], since a
 * READ_ONLY Membership should still be able to view this.
 */
fun Route.moneyVelocityRoutes(
    computeMoneyVelocityUseCase: ComputeMoneyVelocityUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/money-velocity") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)

        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId) ?: return@get

        when (val result = computeMoneyVelocityUseCase.execute(companyId)) {
            is ComputeMoneyVelocityUseCase.Result.Success -> call.respond(
                MoneyVelocityResponseDto(
                    periodId = result.period.id.value.toString(),
                    periodStartDate = result.period.startDate.toString(),
                    netIncome = result.netIncome.amount.toPlainString(),
                    dailyRate = result.dailyRate.amount.toPlainString(),
                    currency = result.netIncome.currency.currencyCode,
                    daysElapsed = result.daysElapsed
                )
            )
            ComputeMoneyVelocityUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeMoneyVelocityUseCase.Result.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            ComputeMoneyVelocityUseCase.Result.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
        }
    }
}
