package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeReceivablesPayablesSummaryUseCase
import com.theprodeogroup.fish.domain.ledger.ReceivablesPayablesSummary
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/receivables-payables-summary` - what the Company is owed and what it owes, with the
 * overdue part (unpaid and older than 30 days, labelled as such; Femi, 2026-10-12) for the Business Owner dashboard
 * (docs/ea-dashboard-playbook; the contract was sent to EA and WEB before this was built). People only: the dashboard
 * calls it with the signed-in person's own token, so it is on no service allow-list. Standard Company sequence.
 */
fun Route.receivablesPayablesSummaryRoutes(
    computeReceivablesPayablesSummaryUseCase: ComputeReceivablesPayablesSummaryUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/receivables-payables-summary") {
        val companyUuid = call.parseUuid(call.parameters["companyId"] ?: "") ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeReceivablesPayablesSummaryUseCase.execute(companyId)) {
            is ComputeReceivablesPayablesSummaryUseCase.Result.Success -> call.respond(result.summary.toDto())
            ComputeReceivablesPayablesSummaryUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
        }
    }
}

private fun ReceivablesPayablesSummary.toDto() = ReceivablesPayablesSummaryResponseDto(
    currency = currency.currencyCode,
    asOf = asOf.toString(),
    receivables = receivables.toDto(),
    payables = payables.toDto()
)

private fun ReceivablesPayablesSummary.Side.toDto() =
    ReceivablesPayablesSideDto(total.amount.toPlainString(), overdueTotal.amount.toPlainString(), overdueCount)
