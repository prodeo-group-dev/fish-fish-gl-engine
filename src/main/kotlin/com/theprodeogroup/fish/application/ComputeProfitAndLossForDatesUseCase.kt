package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.ProfitAndLossForDates
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * Profit and Loss for an explicit date range (UAT v2.2), the counterpart of
 * [ComputeProfitAndLossUseCase] for month-end and year-end packs. A separate use case, not a
 * parameter on the existing one, so the existing response and its strict readers stay as they are.
 * Needs no open Period: a range is read from entries by date.
 */
class ComputeProfitAndLossForDatesUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(val profitAndLoss: ProfitAndLossForDates) : Result()
        data object CompanyNotFound : Result()
        data object NoAccountsForCompany : Result()
    }

    fun execute(companyId: CompanyId, from: LocalDate, to: LocalDate): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        if (accounts.isEmpty()) return Result.NoAccountsForCompany
        val entries = journalEntryRepository.findAllByCompany(companyId)

        return Result.Success(ProfitAndLossForDates.of(accounts, entries, from, to, company.baseCurrency))
    }
}
