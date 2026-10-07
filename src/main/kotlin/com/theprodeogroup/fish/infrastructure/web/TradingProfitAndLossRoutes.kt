package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ClassifyExpenseAccountUseCase
import com.theprodeogroup.fish.application.ComputeTradingProfitAndLossUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/**
 * The trading profit-and-loss report and the account re-tagging route that
 * feeds it (2026-10-07, EA/Femi) - see [ComputeTradingProfitAndLossUseCase] and
 * [ClassifyExpenseAccountUseCase]. Reading needs [authorizeTenantForRead];
 * re-tagging regroups a report, so [authorizeTenantForWrite].
 */
fun Route.tradingProfitAndLossRoutes(
    computeTradingProfitAndLossUseCase: ComputeTradingProfitAndLossUseCase,
    classifyExpenseAccountUseCase: ClassifyExpenseAccountUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/reports/trading-profit-and-loss") {
        val companyId = call.parseCompanyId() ?: return@get
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeTradingProfitAndLossUseCase.execute(companyId)) {
            is ComputeTradingProfitAndLossUseCase.Result.Success -> {
                val r = result.report
                call.respond(
                    TradingProfitAndLossResponseDto(
                        currency = r.currency.currencyCode,
                        periodStart = result.period.startDate.toString(),
                        periodEnd = result.period.endDate.toString(),
                        costOfSalesConfigured = r.costOfSalesConfigured,
                        interestConfigured = r.interestConfigured,
                        revenue = r.revenue.amount.toPlainString(),
                        costOfSales = r.costOfSales.amount.toPlainString(),
                        grossProfit = r.grossProfit.amount.toPlainString(),
                        operatingExpenses = r.operatingExpenses.amount.toPlainString(),
                        operatingProfit = r.operatingProfit.amount.toPlainString(),
                        interestExpense = r.interestExpense.amount.toPlainString(),
                        profitBeforeTax = r.profitBeforeTax.amount.toPlainString(),
                        incomeTaxExpense = r.incomeTaxExpense.amount.toPlainString(),
                        netProfit = r.netProfit.amount.toPlainString()
                    )
                )
            }
            ComputeTradingProfitAndLossUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeTradingProfitAndLossUseCase.Result.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            ComputeTradingProfitAndLossUseCase.Result.NoAccountsForCompany ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_accounts", "This Company has no Chart of Accounts"))
        }
    }

    put("/companies/{companyId}/accounts/{accountId}/expense-classification") {
        val companyId = call.parseCompanyId() ?: return@put
        val accountUuid = call.parseUuid(call.parameters["accountId"] ?: "") ?: return@put
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@put
        if (!call.verifyClaimedTenant(tenantId)) return@put
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@put

        val request = call.receive<ClassifyExpenseAccountRequestDto>()
        val classification = request.expenseClassification?.let {
            try {
                ExpenseClassification.valueOf(it)
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto("bad_request", "expenseClassification must be null or one of ${ExpenseClassification.entries.joinToString()}")
                )
                return@put
            }
        }

        when (val result = classifyExpenseAccountUseCase.execute(companyId, AccountId(accountUuid), classification)) {
            is ClassifyExpenseAccountUseCase.Result.Success -> call.respond(
                ExpenseAccountClassificationDto(
                    result.account.id.value.toString(), result.account.code, result.account.name, result.account.expenseClassification?.name
                )
            )
            ClassifyExpenseAccountUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
            is ClassifyExpenseAccountUseCase.Result.NotAnExpenseAccount ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", result.message ?: "Only an Expense account has an expense classification"))
        }
    }
}

private suspend fun ApplicationCall.parseCompanyId(): CompanyId? {
    val raw = parameters["companyId"]
    if (raw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
        return null
    }
    return parseUuid(raw)?.let(::CompanyId)
}
