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
 * Outcome of [ComputePurchasePostingContextUseCase.execute].
 */
sealed class PurchasePostingContextResult {
    data class Success(
        val periodId: PeriodId,
        val apControlAccountId: AccountId,
        val expenseOrAssetAccountId: AccountId,
        val settlementAccountId: AccountId,
        val currency: Currency
    ) : PurchasePostingContextResult()
    data object CompanyNotFound : PurchasePostingContextResult()
    data object NoOpenPeriod : PurchasePostingContextResult()
    data object ApControlAccountNotConfigured : PurchasePostingContextResult()
    data object ExpenseAccountNotConfigured : PurchasePostingContextResult()
    data object CashAccountNotConfigured : PurchasePostingContextResult()
}

/**
 * `GET /companies/{companyId}/purchase-posting-context` - POP's own
 * counterpart to [ComputeSalesPostingContextUseCase]: resolves the
 * `periodId`/`apControlAccountId`/`expenseOrAssetAccountId`/
 * `settlementAccountId` that POP's thin `/purchasing/record-obligation`
 * and `/purchasing/record-payment` interfaces require the caller to
 * already know, from this Company's own Period/Chart of Accounts,
 * which POP has no direct access to (confirmed: POP's own
 * `PurchaseOrder` has no `companyId` field at all - it's Prodeo
 * Group's own internal trade-finance tool, single-company, not a
 * per-tenant SaaS feature).
 *
 * Account resolution mirrors [ComputeSalesPostingContextUseCase]/
 * `CreateSalesInvoiceUseCase` exactly - same "lowest-coded account of
 * the relevant type" convention already established for Revenue,
 * applied here to Expense; `apControlAccountId` matches
 * [ChartOfAccountsTemplate]'s own fixed "2000" Accounts Payable code
 * the same way AR's "1100" is matched on the sales side;
 * `settlementAccountId` is [ChartOfAccountsTemplate.CASH_CODE] ("1000"),
 * the same Cash account `CreateSalesInvoiceUseCase` already debits for
 * a cash sale.
 */
class ComputePurchasePostingContextUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository
) {
    fun execute(companyId: CompanyId): PurchasePostingContextResult {
        val company = companyRepository.findById(companyId) ?: return PurchasePostingContextResult.CompanyNotFound

        val period = periodRepository.findAllByCompany(companyId).firstOrNull { it.allowsPosting() }
            ?: return PurchasePostingContextResult.NoOpenPeriod

        val accounts = accountRepository.findAllByCompany(companyId)
        val apAccount = accounts.firstOrNull { it.type == AccountType.LIABILITY && it.code == "2000" }
            ?: return PurchasePostingContextResult.ApControlAccountNotConfigured
        val expenseAccount = accounts.filter { it.type == AccountType.EXPENSE }.minByOrNull { it.code }
            ?: return PurchasePostingContextResult.ExpenseAccountNotConfigured
        val cashAccount = accounts.firstOrNull { it.type == AccountType.ASSET && it.code == ChartOfAccountsTemplate.CASH_CODE }
            ?: return PurchasePostingContextResult.CashAccountNotConfigured

        return PurchasePostingContextResult.Success(period.id, apAccount.id, expenseAccount.id, cashAccount.id, company.baseCurrency)
    }
}
