package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.WorkingCapital
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * `GET /companies/{companyId}/reports/working-capital` - a thin read
 * wrapping [WorkingCapital.of], mirroring [ComputeBalanceSheetUseCase]'s
 * exact shape (same "Compute*UseCase, sealed Result" pattern, same
 * `NoAccountsForCompany` guard ahead of [WorkingCapital.of]'s own
 * `require(accounts.isNotEmpty())`). Point-in-time, not scoped to the
 * open Period - see [WorkingCapital]'s own KDoc.
 */
class ComputeWorkingCapitalUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(val workingCapital: WorkingCapital) : Result()
        data object CompanyNotFound : Result()
        data object NoAccountsForCompany : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        if (accounts.isEmpty()) return Result.NoAccountsForCompany
        val entries = journalEntryRepository.findAllByCompany(companyId)

        return Result.Success(WorkingCapital.of(accounts, entries, company.baseCurrency))
    }
}
