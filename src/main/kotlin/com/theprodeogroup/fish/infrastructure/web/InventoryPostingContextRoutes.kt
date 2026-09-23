package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeInventoryPostingContextUseCase
import com.theprodeogroup.fish.application.InventoryPostingContextResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/inventory-posting-context` - IM's
 * counterpart to `purchasePostingContextRoutes`/`salesPostingContextRoutes`:
 * resolves the `periodId`/`apControlAccountId` that GL's own thin
 * `/inventory/record-receipt`/`/inventory/record-issue` interfaces
 * require the caller to already know, since IM has no direct access to
 * this Company's Period/Chart of Accounts. Read-only, so
 * [authorizeTenantForRead], not [authorizeTenantForWrite].
 */
fun Route.inventoryPostingContextRoutes(
    computeInventoryPostingContextUseCase: ComputeInventoryPostingContextUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/inventory-posting-context") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)

        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeInventoryPostingContextUseCase.execute(companyId)) {
            is InventoryPostingContextResult.Success -> call.respond(
                InventoryPostingContextResponseDto(
                    periodId = result.periodId.value.toString(),
                    apControlAccountId = result.apControlAccountId.value.toString(),
                    currency = result.currency.currencyCode
                )
            )
            InventoryPostingContextResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            InventoryPostingContextResult.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            InventoryPostingContextResult.ApControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("ap_control_account_not_configured", "This Company's Chart of Accounts has no Accounts Payable control account (code 2000)"))
        }
    }
}
