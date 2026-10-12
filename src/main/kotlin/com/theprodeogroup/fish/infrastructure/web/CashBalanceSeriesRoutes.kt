package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeCashBalanceSeriesUseCase
import com.theprodeogroup.fish.domain.ledger.CashBalanceSeries
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/cash-balance-series?days=30` - the combined end-of-day balance of the Company's cash
 * and bank books, one point per day, for the Business Owner dashboard's cash trend (docs/ea-dashboard-playbook; the
 * contract was sent to EA and WEB before this was built). People only: the dashboard calls it with the signed-in
 * person's own token, so it is on no service allow-list. Standard Company sequence: the Tenant is resolved from the
 * Company, the claimed Tenant is verified, then read access is checked; `days` is validated after that, so a caller
 * who may not read the Company learns nothing from the answer.
 */
fun Route.cashBalanceSeriesRoutes(
    computeCashBalanceSeriesUseCase: ComputeCashBalanceSeriesUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/cash-balance-series") {
        val companyUuid = call.parseUuid(call.parameters["companyId"] ?: "") ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        val rawDays = call.request.queryParameters["days"]
        val days = if (rawDays == null) DEFAULT_DAYS else rawDays.toIntOrNull()
        if (days == null) {
            call.respond(HttpStatusCode.BadRequest, invalidDays())
            return@get
        }

        when (val result = computeCashBalanceSeriesUseCase.execute(companyId, days)) {
            is ComputeCashBalanceSeriesUseCase.Result.Success -> call.respond(
                CashBalanceSeriesResponseDto(
                    currency = result.series.currency.currencyCode,
                    from = result.series.from.toString(),
                    to = result.series.to.toString(),
                    points = result.series.points.map { CashBalancePointDto(it.date.toString(), it.closingBalance.amount.toPlainString()) }
                )
            )
            ComputeCashBalanceSeriesUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            ComputeCashBalanceSeriesUseCase.Result.InvalidDays -> call.respond(HttpStatusCode.BadRequest, invalidDays())
        }
    }
}

private const val DEFAULT_DAYS = 30

private fun invalidDays() =
    ErrorResponseDto("invalid_days", "days must be a whole number from ${CashBalanceSeries.MIN_DAYS} to ${CashBalanceSeries.MAX_DAYS}")
