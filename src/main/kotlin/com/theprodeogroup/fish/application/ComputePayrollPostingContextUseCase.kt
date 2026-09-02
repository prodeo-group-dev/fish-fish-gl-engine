package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.util.Currency

/**
 * Outcome of [ComputePayrollPostingContextUseCase.execute] - HR/Payroll's
 * counterpart to [PurchasePostingContextResult]/[InventoryPostingContextResult]:
 * resolves the `periodId`/account ids that GL's own thin `/payroll/record-pay-run`,
 * `/leave-accruals/{id}/utilize`, `/leave-accruals/{id}/remeasure` interfaces
 * require the caller to already know, since HR has no direct access to
 * this Company's Period/Chart of Accounts (UC-HR15, "Payroll Cycle Sync
 * - Period Lock Coordination", 2026-09-02). [NoOpenPeriod] is the same
 * check UC-HR15 flagged as missing - HR/Payroll can now check this
 * *before* attempting to run payroll, instead of only discovering a
 * closed Period via `RecordPayRunUseCase`'s own `PeriodNotOpen` failure
 * deep inside the posting call.
 */
sealed class PayrollPostingContextResult {
    data class Success(
        val periodId: PeriodId,
        val wagesExpenseAccountId: AccountId,
        val salariesExpenseAccountId: AccountId,
        val cashAccountId: AccountId,
        val accruedLeaveLiabilityAccountId: AccountId,
        val leaveExpenseAccountId: AccountId,
        val currency: Currency
    ) : PayrollPostingContextResult()
    data object CompanyNotFound : PayrollPostingContextResult()
    data object NoOpenPeriod : PayrollPostingContextResult()
    data object WagesExpenseAccountNotConfigured : PayrollPostingContextResult()
    data object SalariesExpenseAccountNotConfigured : PayrollPostingContextResult()
    data object CashAccountNotConfigured : PayrollPostingContextResult()
    data object AccruedLeaveLiabilityAccountNotConfigured : PayrollPostingContextResult()
    data object LeaveExpenseAccountNotConfigured : PayrollPostingContextResult()
}

class ComputePayrollPostingContextUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository
) {
    fun execute(companyId: CompanyId): PayrollPostingContextResult {
        val company = companyRepository.findById(companyId) ?: return PayrollPostingContextResult.CompanyNotFound
        val period = periodRepository.findAllByCompany(companyId).firstOrNull { it.allowsPosting() }
            ?: return PayrollPostingContextResult.NoOpenPeriod
        val accounts = accountRepository.findAllByCompany(companyId)
        val wagesAccount = accounts.firstOrNull { it.type == AccountType.EXPENSE && it.code == ChartOfAccountsTemplate.WAGES_EXPENSE_CODE }
            ?: return PayrollPostingContextResult.WagesExpenseAccountNotConfigured
        val salariesAccount = accounts.firstOrNull { it.type == AccountType.EXPENSE && it.code == ChartOfAccountsTemplate.SALARIES_EXPENSE_CODE }
            ?: return PayrollPostingContextResult.SalariesExpenseAccountNotConfigured
        val cashAccount = accounts.firstOrNull { it.type == AccountType.ASSET && it.code == ChartOfAccountsTemplate.CASH_CODE }
            ?: return PayrollPostingContextResult.CashAccountNotConfigured
        val liabilityAccount = accounts.firstOrNull { it.type == AccountType.LIABILITY && it.code == ChartOfAccountsTemplate.ACCRUED_LEAVE_LIABILITY_CODE }
            ?: return PayrollPostingContextResult.AccruedLeaveLiabilityAccountNotConfigured
        val leaveExpenseAccount = accounts.firstOrNull { it.type == AccountType.EXPENSE && it.code == ChartOfAccountsTemplate.LEAVE_EXPENSE_CODE }
            ?: return PayrollPostingContextResult.LeaveExpenseAccountNotConfigured
        return PayrollPostingContextResult.Success(
            period.id, wagesAccount.id, salariesAccount.id, cashAccount.id,
            liabilityAccount.id, leaveExpenseAccount.id, company.baseCurrency
        )
    }
}
