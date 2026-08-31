package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.util.Currency

/**
 * Outcome of [ComputeSalesPostingContextUseCase.execute].
 */
sealed class SalesPostingContextResult {
    data class Success(
        val periodId: PeriodId,
        val arControlAccountId: AccountId,
        val revenueAccountId: AccountId,
        val currency: Currency
    ) : SalesPostingContextResult()
    data object CompanyNotFound : SalesPostingContextResult()
    data object NoOpenPeriod : SalesPostingContextResult()
    data object ArControlAccountNotConfigured : SalesPostingContextResult()
    data object RevenueAccountNotConfigured : SalesPostingContextResult()
}

/**
 * `GET /companies/{companyId}/sales-posting-context` - the read-only
 * counterpart to [RecordSaleUseCase]'s caller-supplied `periodId`/
 * `arControlAccountId`/`revenueAccountId`, so an external caller with
 * no direct access to this Company's Period/Chart of Accounts (SOP, in
 * particular) can resolve them before calling the thin posting
 * interface.
 *
 * Resolution logic is copied from [CreateSalesInvoiceUseCase] rather
 * than shared with it - deliberately: that use case resolves accounts
 * as one step among many inside a larger posting flow, this use case's
 * whole job is exposing that same resolution as its own queryable
 * fact for an out-of-repo caller. Account code "1100" (AR control) and
 * the lowest-coded REVENUE-type account both come from
 * `ChartOfAccountsTemplate`, same as there.
 */
class ComputeSalesPostingContextUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository
) {
    fun execute(companyId: CompanyId): SalesPostingContextResult {
        val company = companyRepository.findById(companyId) ?: return SalesPostingContextResult.CompanyNotFound

        val period = periodRepository.findAllByCompany(companyId).firstOrNull { it.allowsPosting() }
            ?: return SalesPostingContextResult.NoOpenPeriod

        val accounts = accountRepository.findAllByCompany(companyId)
        val arAccount = accounts.firstOrNull { it.type == AccountType.ASSET && it.code == "1100" }
            ?: return SalesPostingContextResult.ArControlAccountNotConfigured
        val revenueAccount = accounts.filter { it.type == AccountType.REVENUE }.minByOrNull { it.code }
            ?: return SalesPostingContextResult.RevenueAccountNotConfigured

        return SalesPostingContextResult.Success(period.id, arAccount.id, revenueAccount.id, company.baseCurrency)
    }
}
