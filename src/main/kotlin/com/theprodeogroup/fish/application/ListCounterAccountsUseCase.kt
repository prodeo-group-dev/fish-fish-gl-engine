package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.ModuleOwnedAccounts
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * `GET /companies/{companyId}/cash-books/{accountId}/counter-accounts?direction=in|out` (docs/GL_Cash_And_Bank_Books_SRS.md,
 * Release B): the accounts a person may pick as "what was this money for" (money in) or "what was it spent on"
 * (money out). It is the exact complement of the refusal in [RecordCashBookEntryUseCase]: an account is listed only if
 * recording against it would be accepted, so a screen never offers a control account, another cash or bank book, or an
 * inactive account, and never has to copy the exclusion list.
 *
 * Each account carries a plain [Group] so a screen can show "Income", "Loans", "Owner's money" and the expense kinds
 * instead of debit and credit.
 */
class ListCounterAccountsUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository
) {
    enum class Group { INCOME, LOAN, OWNERS_MONEY, EXPENSE, OTHER }

    /** [group] and, for an expense, its finer kind: `EXPENSE_COST_OF_GOODS_SOLD`, `EXPENSE_OTHER`, and so on. */
    data class Option(val account: Account, val group: String)

    sealed class Result {
        data class Success(val options: List<Option>) : Result()
        data object CompanyNotFound : Result()
        data object AccountNotFound : Result()
        data object NotACashBook : Result()
    }

    @Suppress("UNUSED_PARAMETER")
    fun execute(companyId: CompanyId, bookAccountId: AccountId, direction: CashDirection): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val book = accountRepository.findById(bookAccountId)?.takeIf { it.companyId == companyId } ?: return Result.AccountNotFound
        if (book.cashBookKind == null) return Result.NotACashBook

        val options = accountRepository.findAllByCompany(companyId)
            .filter { it.active && it.id != book.id && ModuleOwnedAccounts.useInstead(it, company.clientType) == null }
            .map { Option(it, groupOf(it)) }
            .sortedWith(compareBy({ groupOrder(it.group) }, { it.account.code }))
        // `direction` does not narrow the list today: a refund is money in against an expense, a supplier credit is
        // money out against income. It is part of the route so the rule can differ per direction later without a new route.
        return Result.Success(options)
    }

    private fun groupOf(account: Account): String = when (account.type) {
        AccountType.REVENUE -> Group.INCOME.name
        AccountType.EQUITY -> Group.OWNERS_MONEY.name
        AccountType.EXPENSE -> "EXPENSE_" + (account.expenseClassification?.name ?: Group.OTHER.name)
        AccountType.LIABILITY -> if (account.classification == AccountClassification.NON_CURRENT) Group.LOAN.name else Group.OTHER.name
        AccountType.ASSET -> Group.OTHER.name
    }

    private fun groupOrder(group: String): Int = when {
        group == Group.INCOME.name -> 0
        group.startsWith("EXPENSE_") -> 1
        group == Group.LOAN.name -> 2
        group == Group.OWNERS_MONEY.name -> 3
        else -> 4
    }
}
