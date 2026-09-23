package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeSalesPostingContextUseCase
import com.theprodeogroup.fish.application.SalesPostingContextResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/sales-posting-context` - lets an
 * external caller with no direct access to this Company's Period/
 * Chart of Accounts (SOP, specifically - see `ComputeSalesPostingContextUseCase`'s
 * own KDoc) resolve the `periodId`/`arControlAccountId`/`revenueAccountId`
 * that GL's own thin `/sales/record-sale` interface requires the
 * caller to already know. Read-only, so [authorizeTenantForRead] like
 * [moneyVelocityRoutes], not [authorizeTenantForWrite].
 */
fun Route.salesPostingContextRoutes(
    computeSalesPostingContextUseCase: ComputeSalesPostingContextUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/sales-posting-context") {
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

        when (val result = computeSalesPostingContextUseCase.execute(companyId)) {
            is SalesPostingContextResult.Success -> call.respond(
                SalesPostingContextResponseDto(
                    periodId = result.periodId.value.toString(),
                    arControlAccountId = result.arControlAccountId.value.toString(),
                    revenueAccountId = result.revenueAccountId.value.toString(),
                    vatControlAccountId = result.vatControlAccountId.value.toString(),
                    currency = result.currency.currencyCode
                )
            )
            SalesPostingContextResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            SalesPostingContextResult.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            SalesPostingContextResult.ArControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("ar_control_account_not_configured", "This Company's Chart of Accounts has no Accounts Receivable control account (code 1100)"))
            SalesPostingContextResult.RevenueAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("revenue_account_not_configured", "This Company's Chart of Accounts has no Revenue account"))
            SalesPostingContextResult.VatControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("vat_control_account_not_configured", "This Company's Chart of Accounts has no VAT Control Account (code ${com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate.VAT_CONTROL_ACCOUNT_CODE})"))
        }
    }
}
