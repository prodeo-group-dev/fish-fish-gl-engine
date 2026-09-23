package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputePurchasePostingContextUseCase
import com.theprodeogroup.fish.application.PurchasePostingContextResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/purchase-posting-context` - lets POP
 * (which has no direct access to this Company's Period/Chart of
 * Accounts) resolve the `periodId`/`apControlAccountId`/
 * `expenseOrAssetAccountId`/`settlementAccountId` that GL's own thin
 * `/purchasing/record-obligation`/`/purchasing/record-payment`
 * interfaces require the caller to already know - POP's own
 * counterpart to `salesPostingContextRoutes`. Read-only, so
 * [authorizeTenantForRead], not [authorizeTenantForWrite].
 */
fun Route.purchasePostingContextRoutes(
    computePurchasePostingContextUseCase: ComputePurchasePostingContextUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/purchase-posting-context") {
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

        when (val result = computePurchasePostingContextUseCase.execute(companyId)) {
            is PurchasePostingContextResult.Success -> call.respond(
                PurchasePostingContextResponseDto(
                    periodId = result.periodId.value.toString(),
                    apControlAccountId = result.apControlAccountId.value.toString(),
                    expenseOrAssetAccountId = result.expenseOrAssetAccountId.value.toString(),
                    settlementAccountId = result.settlementAccountId.value.toString(),
                    vatControlAccountId = result.vatControlAccountId.value.toString(),
                    currency = result.currency.currencyCode,
                    facilityLiabilityAccountId = result.facilityLiabilityAccountId?.value?.toString()
                )
            )
            PurchasePostingContextResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            PurchasePostingContextResult.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            PurchasePostingContextResult.ApControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("ap_control_account_not_configured", "This Company's Chart of Accounts has no Accounts Payable control account (code 2000)"))
            PurchasePostingContextResult.ExpenseAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("expense_account_not_configured", "This Company's Chart of Accounts has no Expense account"))
            PurchasePostingContextResult.CashAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("cash_account_not_configured", "This Company's Chart of Accounts has no Cash account (code 1000)"))
            PurchasePostingContextResult.VatControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("vat_control_account_not_configured", "This Company's Chart of Accounts has no VAT Control Account (code ${com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate.VAT_CONTROL_ACCOUNT_CODE})"))
        }
    }
}
