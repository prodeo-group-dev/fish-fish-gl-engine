package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeSalesToExpenseRatioUseCase
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/sales-to-expense-ratio` - the third
 * dashboard KPI (see `ComputeSalesToExpenseRatioUseCase`'s own KDoc).
 * Mirrors `moneyVelocityRoutes`/`expenseVelocityRoutes` exactly, including
 * their read-only authorization; 409 covers all three "nothing to show
 * yet" states (no open Period, no Chart of Accounts, no operating
 * expense posted yet).
 */
fun Route.salesToExpenseRatioRoutes(
    computeSalesToExpenseRatioUseCase: ComputeSalesToExpenseRatioUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/sales-to-expense-ratio") {
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

        when (val result = computeSalesToExpenseRatioUseCase.execute(companyId)) {
            is ComputeSalesToExpenseRatioUseCase.Result.Success -> call.respond(
                SalesToExpenseRatioResponseDto(
                    periodId = result.period.id.value.toString(),
                    periodStartDate = result.period.startDate.toString(),
                    totalRevenue = result.totalRevenue.amount.toPlainString(),
                    operatingExpense = result.operatingExpense.amount.toPlainString(),
                    currency = result.totalRevenue.currency.currencyCode,
                    ratio = result.ratio.toPlainString()
                )
            )
            ComputeSalesToExpenseRatioUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeSalesToExpenseRatioUseCase.Result.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            ComputeSalesToExpenseRatioUseCase.Result.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
            ComputeSalesToExpenseRatioUseCase.Result.NoOperatingExpenseYet ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_operating_expense", "No operating expense posted yet this Period"))
        }
    }
}
