package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * `PUT /companies/{companyId}/accounts/{accountId}/expense-classification`
 * (2026-10-07, EA/Femi): re-tags an existing Expense account as cost of sales,
 * operating, interest or income tax (or clears the tag), so a Company created
 * before the reporting accounts existed - or whose cost-of-sales account was
 * named by IM/POP/SOP - can feed the trading P&L correctly.
 *
 * It regroups the trading P&L; it never changes a balance. An account of
 * another Company is reported as not found (never a distinct "forbidden", so
 * this never confirms another Company's account exists). There is no audit
 * trail of the change yet (the append-only audit work is on the backlog).
 */
class ClassifyExpenseAccountUseCase(
    private val accountRepository: AccountRepository
) {
    sealed class Result {
        data class Success(val account: Account) : Result()
        data object AccountNotFound : Result()
        data class NotAnExpenseAccount(val message: String?) : Result()
    }

    fun execute(companyId: CompanyId, accountId: AccountId, expenseClassification: ExpenseClassification?): Result {
        val account = accountRepository.findById(accountId)
            ?.takeIf { it.companyId == companyId }
            ?: return Result.AccountNotFound
        val outcome = account.reclassifyExpense(expenseClassification)
        if (!outcome.isValid) return Result.NotAnExpenseAccount(outcome.errors.firstOrNull())
        accountRepository.save(account)
        return Result.Success(account)
    }
}
