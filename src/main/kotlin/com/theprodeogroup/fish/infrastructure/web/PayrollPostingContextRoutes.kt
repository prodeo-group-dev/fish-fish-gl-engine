package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputePayrollPostingContextUseCase
import com.theprodeogroup.fish.application.PayrollPostingContextResult
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/payroll-posting-context` (UC-HR15,
 * 2026-09-02) - HR's counterpart to `purchasePostingContextRoutes`/
 * `salesPostingContextRoutes`/`inventoryPostingContextRoutes`: resolves
 * the `periodId`/wages-expense/salaries-expense/cash/accrued-leave-liability/
 * leave-expense account ids that GL's own thin payroll interfaces
 * require the caller to already know, since HR has no direct access to
 * this Company's Period/Chart of Accounts. Also doubles as the period-lock
 * coordination check UC-HR15 named as missing - a 409 `no_open_period`
 * here is exactly the "check FiSH's period status before running
 * payroll" HR/Payroll previously had no way to do. Read-only, so
 * [authorizeTenantForRead], not [authorizeTenantForWrite].
 */
fun Route.payrollPostingContextRoutes(
    computePayrollPostingContextUseCase: ComputePayrollPostingContextUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/payroll-posting-context") {
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

        when (val result = computePayrollPostingContextUseCase.execute(companyId)) {
            is PayrollPostingContextResult.Success -> call.respond(
                PayrollPostingContextResponseDto(
                    periodId = result.periodId.value.toString(),
                    wagesExpenseAccountId = result.wagesExpenseAccountId.value.toString(),
                    salariesExpenseAccountId = result.salariesExpenseAccountId.value.toString(),
                    cashAccountId = result.cashAccountId.value.toString(),
                    accruedLeaveLiabilityAccountId = result.accruedLeaveLiabilityAccountId.value.toString(),
                    leaveExpenseAccountId = result.leaveExpenseAccountId.value.toString(),
                    currency = result.currency.currencyCode
                )
            )
            PayrollPostingContextResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            PayrollPostingContextResult.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
            PayrollPostingContextResult.WagesExpenseAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("wages_expense_account_not_configured", "This Company's Chart of Accounts has no Wages Expense account (code ${com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate.WAGES_EXPENSE_CODE})"))
            PayrollPostingContextResult.SalariesExpenseAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("salaries_expense_account_not_configured", "This Company's Chart of Accounts has no Salaries Expense account (code ${com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate.SALARIES_EXPENSE_CODE})"))
            PayrollPostingContextResult.CashAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("cash_account_not_configured", "This Company's Chart of Accounts has no Cash account (code ${com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate.CASH_CODE})"))
            PayrollPostingContextResult.AccruedLeaveLiabilityAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("accrued_leave_liability_account_not_configured", "This Company's Chart of Accounts has no Accrued Leave Liability account (code ${com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate.ACCRUED_LEAVE_LIABILITY_CODE})"))
            PayrollPostingContextResult.LeaveExpenseAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("leave_expense_account_not_configured", "This Company's Chart of Accounts has no Leave Expense account (code ${com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate.LEAVE_EXPENSE_CODE})"))
        }
    }
}
