package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.BalanceSheet
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * The GL page's "Balance sheet" report sub-page (2026-08-29, "the GL
 * should also have a reports subpage") - a thin read wrapping
 * [BalanceSheet.of], same "Compute*UseCase, sealed Result" shape as
 * [ComputeMoneyVelocityUseCase]. Point-in-time (all posted activity so
 * far), not scoped to the open Period - see [BalanceSheet]'s own KDoc.
 */
class ComputeBalanceSheetUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(val balanceSheet: BalanceSheet) : Result()
        data object CompanyNotFound : Result()
        data object NoAccountsForCompany : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        if (accounts.isEmpty()) return Result.NoAccountsForCompany
        val entries = journalEntryRepository.findAllByCompany(companyId)

        return Result.Success(BalanceSheet.of(accounts, entries, company.baseCurrency))
    }
}
