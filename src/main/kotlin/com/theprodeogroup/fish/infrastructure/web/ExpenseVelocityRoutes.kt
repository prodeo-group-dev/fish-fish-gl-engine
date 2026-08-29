package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeExpenseVelocityUseCase
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/expense-velocity` - [ComputeMoneyVelocityUseCase]'s
 * paired KPI route (see `ComputeExpenseVelocityUseCase`'s own KDoc).
 * Mirrors `moneyVelocityRoutes` exactly, including its read-only
 * authorization.
 */
fun Route.expenseVelocityRoutes(
    computeExpenseVelocityUseCase: ComputeExpenseVelocityUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/expense-velocity") {
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

        when (val result = computeExpenseVelocityUseCase.execute(companyId)) {
            is ComputeExpenseVelocityUseCase.Result.Success -> call.respond(
                ExpenseVelocityResponseDto(
                    periodId = result.period.id.value.toString(),
                    periodStartDate = result.period.startDate.toString(),
                    operatingExpense = result.operatingExpense.amount.toPlainString(),
                    dailyRate = result.dailyRate.amount.toPlainString(),
                    currency = result.operatingExpense.currency.currencyCode,
                    daysElapsed = result.daysElapsed
                )
            )
            ComputeExpenseVelocityUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeExpenseVelocityUseCase.Result.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            ComputeExpenseVelocityUseCase.Result.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
        }
    }
}
