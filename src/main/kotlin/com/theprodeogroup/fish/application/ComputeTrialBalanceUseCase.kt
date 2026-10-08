package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.TrialBalance
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * The Trial Balance report (Femi, 2026-10-08: "a very important report and
 * test of correctness") - a thin read wrapping [TrialBalance.of], same
 * "Compute*UseCase, sealed Result" shape as [ComputeBalanceSheetUseCase].
 * [asOf] limits it to entries dated on or before that day (a month- or
 * year-end pack); `null` means all posted activity so far.
 */
class ComputeTrialBalanceUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(
            val trialBalance: TrialBalance,
            /** Code, name and the rest of each line's Account, so a line can be shown as a real row. */
            val accounts: Map<AccountId, Account>,
            val asOf: LocalDate?
        ) : Result()

        data object CompanyNotFound : Result()
        data object NoAccountsForCompany : Result()
    }

    fun execute(companyId: CompanyId, asOf: LocalDate? = null): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val accounts = accountRepository.findAllByCompany(companyId)
        if (accounts.isEmpty()) return Result.NoAccountsForCompany
        val entries = journalEntryRepository.findAllByCompany(companyId)
            .filter { asOf == null || !it.date.isAfter(asOf) }

        return Result.Success(
            TrialBalance.of(accounts, entries, company.baseCurrency),
            accounts.associateBy { it.id },
            asOf
        )
    }
}
