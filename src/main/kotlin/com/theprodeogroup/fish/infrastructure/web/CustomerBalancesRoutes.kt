package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeCustomerBalancesResult
import com.theprodeogroup.fish.application.ComputeCustomerBalancesUseCase
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /companies/{companyId}/customer-balances` (2026-09-04) -
 * [ComputeCustomerBalancesUseCase]'s inbound HTTP surface. `POST`, not
 * `GET`, since the caller-supplied `customerIds` list can be long
 * enough to matter as a request body rather than a query string. Read-
 * only, so [authorizeTenantForRead] like [salesPostingContextRoutes].
 */
fun Route.customerBalancesRoutes(
    computeCustomerBalancesUseCase: ComputeCustomerBalancesUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/customer-balances") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@post
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@post
        val companyId = CompanyId(companyUuid)

        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForRead(tenantId, companyId) ?: return@post

        val request = call.receive<ComputeCustomerBalancesRequestDto>()
        val customerIds = mutableListOf<CustomerId>()
        for (raw in request.customerIds) {
            val uuid = call.parseUuid(raw) ?: return@post
            customerIds.add(CustomerId(uuid))
        }

        when (val result = computeCustomerBalancesUseCase.execute(companyId, customerIds)) {
            is ComputeCustomerBalancesResult.Success -> call.respond(
                ComputeCustomerBalancesResponseDto(
                    result.balances.map { CustomerBalanceDto(it.customerId.value.toString(), it.balance.amount.toPlainString(), it.balance.currency.currencyCode) }
                )
            )
            ComputeCustomerBalancesResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeCustomerBalancesResult.ArControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("ar_control_account_not_configured", "This Company's Chart of Accounts has no Accounts Receivable control account (code 1100)"))
        }
    }
}
