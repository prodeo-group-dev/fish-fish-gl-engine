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
 * Posts (or adjusts) one Account's opening balance against a
 * caller-supplied contra-account - a standing, always-available
 * action, not the one-shot field [OnboardTenantUseCase]/
 * [AddCompanyToTenantUseCase] offer only at onboarding time
 * (2026-09-03, "Most users are expected to set up from incomplete
 * records, that is why the opening figures for the first fiscal year
 * should be available throughout the year"). Onboarding's own
 * `openingCashBalance` still exists unchanged (Cash is the one figure
 * almost every onboarding flow already asks for, and it resolves its
 * own contra-account locally rather than calling this use case at
 * all) - this is the general mechanism for every *other* account a
 * business discovers it needs a catch-up figure for: a newly set-up
 * Fixed Asset account, a Long-term Liability, anything.
 *
 * **[contraAccountId] is caller-supplied, not resolved by an implicit
 * code lookup (2026-09-12)** - this used to always resolve
 * [ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE] internally,
 * the one place in this codebase that didn't follow the otherwise-
 * universal "caller supplies every `AccountId` explicitly" convention
 * (`RecordVendorObligationUseCase`, `PostJournalEntryUseCase`,
 * `RecordInventoryReceiptUseCase` all do). Generalizing it is what
 * lets this same, already-tested mechanism serve both a genuine
 * opening balance (contra = Opening Balance Equity) and a not-yet-
 * classified correction like a Fixed Asset discovered after the fact
 * (contra = the newer, deliberately separate Suspense Account,
 * [ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE]) - "journalled out
 * to its true classification later" is exactly the Suspense Account's
 * own purpose, not Opening Balance Equity's.
 *
 * **[amount] is always entered as a positive, plain-language figure -
 * the caller never picks debit/credit.** [AccountType.normalBalance]
 * decides which side the account itself takes (DEBIT for Asset/
 * Expense, CREDIT for Liability/Equity/Revenue) so "this account's
 * opening balance is 5,000" means the same intuitive thing regardless
 * of which side of the accounting equation the account sits on - the
 * contra-account always takes the opposite side, keeping the entry
 * balanced.
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
        val contraAccountId: AccountId,
        val amount: BigDecimal,
        val date: LocalDate
    )

    sealed class Result {
        data class Success(val journalEntry: JournalEntry) : Result()
        data object CompanyNotFound : Result()
        data object AccountNotFound : Result()
        data object ContraAccountNotFound : Result()
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

        val contraAccount = accountRepository.findById(request.contraAccountId)
            ?.takeIf { it.companyId == request.companyId }
            ?: return Result.ContraAccountNotFound

        val period = periodRepository.findAllByCompany(request.companyId)
            .filter { it.allowsPosting() }
            .firstOrNull { !request.date.isBefore(it.startDate) && !request.date.isAfter(it.endDate) }
            ?: return Result.NoOpenPeriod

        val amount = Money(request.amount, company.baseCurrency)
        val accountSide = account.type.normalBalance()
        val contraSide = if (accountSide == TransactionSide.DEBIT) TransactionSide.CREDIT else TransactionSide.DEBIT

        val entry = JournalEntry.create(
            period.id,
            request.date,
            listOf(
                JournalLine(account.id, amount, accountSide),
                JournalLine(contraAccount.id, amount, contraSide)
            ),
            JournalSource.MANUAL,
            "Opening balance - ${account.name}"
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordOpeningBalanceUseCase built a JournalEntry that failed to post: ${posting.errors.joinToString()}"
        }

        account.recordActivity()
        contraAccount.recordActivity()
        accountRepository.save(account)
        accountRepository.save(contraAccount)
        journalEntryRepository.save(entry)

        return Result.Success(entry)
    }
}
