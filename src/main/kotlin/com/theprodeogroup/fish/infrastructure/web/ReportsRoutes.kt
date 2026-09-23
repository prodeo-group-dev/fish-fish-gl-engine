package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/reports/{balance-sheet,profit-and-loss,cash-flow}` -
 * the GL page's "Reports" sub-page (2026-08-29, "the GL should also have
 * a reports subpage ... along with a journal Posting subpage and its
 * Dashboard"). Same read-only, Company-scoped shape as
 * `moneyVelocityRoutes`/`expenseVelocityRoutes` - [authorizeTenantForRead],
 * a READ_ONLY Membership can view every report here.
 */
fun Route.reportsRoutes(
    computeBalanceSheetUseCase: ComputeBalanceSheetUseCase,
    computeProfitAndLossUseCase: ComputeProfitAndLossUseCase,
    computeCashFlowUseCase: ComputeCashFlowUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/reports/balance-sheet") {
        val companyId = call.parseCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeBalanceSheetUseCase.execute(companyId)) {
            is ComputeBalanceSheetUseCase.Result.Success -> {
                val bs = result.balanceSheet
                call.respond(
                    BalanceSheetResponseDto(
                        currency = bs.currency.currencyCode,
                        assetLines = bs.assetLines.map { it.toDto() },
                        liabilityLines = bs.liabilityLines.map { it.toDto() },
                        equityLines = bs.equityLines.map { it.toDto() },
                        retainedEarnings = bs.retainedEarnings.amount.toPlainString(),
                        totalAssets = bs.totalAssets.amount.toPlainString(),
                        totalLiabilities = bs.totalLiabilities.amount.toPlainString(),
                        totalEquity = bs.totalEquity.amount.toPlainString(),
                        isBalanced = bs.isBalanced
                    )
                )
            }
            ComputeBalanceSheetUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeBalanceSheetUseCase.Result.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
        }
    }

    get("/companies/{companyId}/reports/profit-and-loss") {
        val companyId = call.parseCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeProfitAndLossUseCase.execute(companyId)) {
            is ComputeProfitAndLossUseCase.Result.Success -> {
                val pnl = result.profitAndLoss
                call.respond(
                    ProfitAndLossResponseDto(
                        periodId = pnl.periodId.value.toString(),
                        currency = pnl.currency.currencyCode,
                        totalRevenue = pnl.totalRevenue.amount.toPlainString(),
                        totalExpense = pnl.totalExpense.amount.toPlainString(),
                        netIncome = pnl.netIncome.amount.toPlainString()
                    )
                )
            }
            ComputeProfitAndLossUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeProfitAndLossUseCase.Result.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            ComputeProfitAndLossUseCase.Result.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
        }
    }

    get("/companies/{companyId}/reports/cash-flow") {
        val companyId = call.parseCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeCashFlowUseCase.execute(companyId)) {
            is ComputeCashFlowUseCase.Result.Success -> {
                val cf = result.statementOfCashFlows
                call.respond(
                    CashFlowResponseDto(
                        currency = cf.currency.currencyCode,
                        startDate = cf.startDate.toString(),
                        endDate = cf.endDate.toString(),
                        openingBalance = cf.openingBalance.amount.toPlainString(),
                        closingBalance = cf.closingBalance.amount.toPlainString(),
                        netCashFlow = cf.netCashFlow.amount.toPlainString(),
                        activityAmounts = cf.activityAmounts.map {
                            CashFlowActivityAmountDto(it.activity.name, it.netAmount.amount.toPlainString())
                        },
                        uncategorizedAmount = cf.uncategorizedAmount.amount.toPlainString()
                    )
                )
            }
            ComputeCashFlowUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeCashFlowUseCase.Result.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            ComputeCashFlowUseCase.Result.NoCashAccount ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_cash_account", "This Company has no Cash account"))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.parseCompanyId(): CompanyId? {
    val companyIdRaw = parameters["companyId"]
    if (companyIdRaw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
        return null
    }
    val companyUuid = parseUuid(companyIdRaw) ?: return null
    return CompanyId(companyUuid)
}

private fun com.theprodeogroup.fish.domain.ledger.BalanceSheetLine.toDto() = BalanceSheetLineDto(
    accountId = accountId.value.toString(),
    code = code,
    name = name,
    classification = classification?.name,
    balance = balance.amount.toPlainString()
)
