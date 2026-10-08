package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeBalanceSheetUseCase
import com.theprodeogroup.fish.application.ComputeCashFlowUseCase
import com.theprodeogroup.fish.application.ComputeProfitAndLossUseCase
import com.theprodeogroup.fish.application.ComputeTrialBalanceUseCase
import com.theprodeogroup.fish.application.ComputeWorkingCapitalUseCase
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/reports/{balance-sheet,profit-and-loss,cash-flow,working-capital}` -
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
    computeWorkingCapitalUseCase: ComputeWorkingCapitalUseCase,
    computeTrialBalanceUseCase: ComputeTrialBalanceUseCase,
    companyRepository: CompanyRepository
) {
    // The Ledger's test of correctness (Femi, 2026-10-08): a debit column and a credit column that
    // must agree. `?asOf=YYYY-MM-DD` limits it to entries dated on or before that day.
    get("/companies/{companyId}/reports/trial-balance") {
        val companyId = call.parseCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        val asOfRaw = call.request.queryParameters["asOf"]
        val asOf = if (asOfRaw == null) null else try {
            java.time.LocalDate.parse(asOfRaw)
        } catch (e: java.time.format.DateTimeParseException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_as_of", "asOf must be a date, YYYY-MM-DD"))
            return@get
        }

        when (val result = computeTrialBalanceUseCase.execute(companyId, asOf)) {
            is ComputeTrialBalanceUseCase.Result.Success -> {
                val tb = result.trialBalance
                call.respond(
                    TrialBalanceResponseDto(
                        currency = tb.currency.currencyCode,
                        asOf = result.asOf?.toString(),
                        lines = tb.lines
                            .map { line ->
                                val account = result.accounts.getValue(line.accountId)
                                TrialBalanceLineDto(
                                    accountId = line.accountId.value.toString(),
                                    code = account.code,
                                    name = account.name,
                                    type = line.accountType.name,
                                    debit = line.debit.amount.toPlainString(),
                                    credit = line.credit.amount.toPlainString()
                                )
                            }
                            .sortedBy { it.code },
                        totalDebits = tb.totalDebits.amount.toPlainString(),
                        totalCredits = tb.totalCredits.amount.toPlainString(),
                        difference = (tb.totalDebits - tb.totalCredits).amount.toPlainString(),
                        isBalanced = tb.totalDebits == tb.totalCredits
                    )
                )
            }
            ComputeTrialBalanceUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeTrialBalanceUseCase.Result.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
        }
    }

    get("/companies/{companyId}/reports/balance-sheet") {
        val companyId = call.parseCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        // `?asOf=YYYY-MM-DD` (optional): the sheet as at that day. Deliberately a request parameter only -
        // no response field, since EA decodes this response strictly.
        val asOfRaw = call.request.queryParameters["asOf"]
        val asOf = if (asOfRaw == null) null else try {
            java.time.LocalDate.parse(asOfRaw)
        } catch (e: java.time.format.DateTimeParseException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_as_of", "asOf must be a date, YYYY-MM-DD"))
            return@get
        }

        when (val result = computeBalanceSheetUseCase.execute(companyId, asOf)) {
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

    get("/companies/{companyId}/reports/working-capital") {
        val companyId = call.parseCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeWorkingCapitalUseCase.execute(companyId)) {
            is ComputeWorkingCapitalUseCase.Result.Success -> {
                val wc = result.workingCapital
                call.respond(
                    WorkingCapitalResponseDto(
                        currency = wc.currency.currencyCode,
                        totalCurrentAssets = wc.totalCurrentAssets.amount.toPlainString(),
                        totalCurrentLiabilities = wc.totalCurrentLiabilities.amount.toPlainString(),
                        workingCapital = wc.workingCapital.amount.toPlainString()
                    )
                )
            }
            ComputeWorkingCapitalUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeWorkingCapitalUseCase.Result.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
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
