package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Posts (or adjusts) one Account's opening balance against Opening
 * Balance Equity - a standing, always-available action, not the
 * one-shot field [OnboardTenantUseCase]/[AddCompanyToTenantUseCase]
 * offer only at onboarding time (2026-09-03, "Most users are expected
 * to set up from incomplete records, that is why the opening figures
 * for the first fiscal year should be available throughout the
 * year"). Onboarding's own `openingCashBalance` still exists
 * unchanged (Cash is the one figure almost every onboarding flow
 * already asks for) - this is the general mechanism for every *other*
 * account a business discovers it needs an opening figure for as it
 * gets its books organized: a newly set-up Fixed Asset account, a
 * Long-term Liability, anything.
 *
 * **[amount] is always entered as a positive, plain-language figure -
 * the caller never picks debit/credit.** [AccountType.normalBalance]
 * decides which side the account itself takes (DEBIT for Asset/
 * Expense, CREDIT for Liability/Equity/Revenue) so "this account's
 * opening balance is 5,000" means the same intuitive thing regardless
 * of which side of the accounting equation the account sits on -
 * Opening Balance Equity always takes the opposite side, keeping the
 * entry balanced.
 *
 * **No "only before real activity" or "only in the first fiscal year"
 * gate** - deliberately not invented here. The only real constraint is
 * the same one every other posting already has: there has to be an
 * open Period covering [date]. Restricting this further wasn't asked
 * for, and this codebase's own "park, don't guess" discipline argues
 * against picking a boundary the user didn't actually state.
 */
class RecordOpeningBalanceUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val companyId: CompanyId,
        val accountId: AccountId,
        val amount: BigDecimal,
        val date: LocalDate
    )

    sealed class Result {
        data class Success(val journalEntry: JournalEntry) : Result()
        data object CompanyNotFound : Result()
        data object AccountNotFound : Result()
        data object OpeningBalanceEquityAccountNotConfigured : Result()
        data object NoOpenPeriod : Result()
        data class InvalidAmount(val message: String) : Result()
    }

    fun execute(request: Request): Result {
        if (request.amount.signum() <= 0) {
            return Result.InvalidAmount("amount must be positive")
        }

        val company = companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound

        val account = accountRepository.findById(request.accountId)
            ?.takeIf { it.companyId == request.companyId }
            ?: return Result.AccountNotFound

        val openingBalanceEquityAccount = accountRepository.findAllByCompany(request.companyId)
            .firstOrNull { it.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE }
            ?: return Result.OpeningBalanceEquityAccountNotConfigured

        val period = periodRepository.findAllByCompany(request.companyId)
            .filter { it.allowsPosting() }
            .firstOrNull { !request.date.isBefore(it.startDate) && !request.date.isAfter(it.endDate) }
            ?: return Result.NoOpenPeriod

        val amount = Money(request.amount, company.baseCurrency)
        val accountSide = account.type.normalBalance()
        val equitySide = if (accountSide == TransactionSide.DEBIT) TransactionSide.CREDIT else TransactionSide.DEBIT

        val entry = JournalEntry.create(
            period.id,
            request.date,
            listOf(
                JournalLine(account.id, amount, accountSide),
                JournalLine(openingBalanceEquityAccount.id, amount, equitySide)
            ),
            JournalSource.MANUAL,
            "Opening balance - ${account.name}"
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordOpeningBalanceUseCase built a JournalEntry that failed to post: ${posting.errors.joinToString()}"
        }

        account.recordActivity()
        openingBalanceEquityAccount.recordActivity()
        accountRepository.save(account)
        accountRepository.save(openingBalanceEquityAccount)
        journalEntryRepository.save(entry)

        return Result.Success(entry)
    }
}
