package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * Adds one Account to a Company's Chart of Accounts (2026-09-03, "We
 * need work on the Chart of Accounts. There is no setup for it.") -
 * a real gap this closes: the only accounts a Company could ever have
 * were whatever [com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate]
 * seeded at onboarding. A business that acquires a new asset, takes on
 * a new loan, or just needs a more specific account than the generic
 * template gave it had no way to add one.
 *
 * **"Fixed" vs "Current" for Assets, "Current" vs "Long term" for
 * Liabilities is exactly [AccountClassification.NON_CURRENT]/[AccountClassification.CURRENT] -
 * no new domain concept.** [ChartOfAccountsTemplate]'s own template
 * already codes "Fixed Assets" as `NON_CURRENT` (code 1200) - this
 * doesn't invent a separate "fixed asset" marker, it just makes the
 * same classification choice available for *any* new Asset/Liability
 * account, not just the four the template happens to seed.
 *
 * **Deliberately out of scope**: linking a Fixed-classified Asset
 * account to an individual [com.theprodeogroup.fish.domain.fixedassets.FixedAsset]
 * register entry (cost/category/useful life/depreciation) - that
 * domain type exists but has no HTTP layer or WEB screen anywhere
 * yet. This use case only gets the *account* itself set up; standing
 * up the Fixed Asset register is a separate, larger piece of work,
 * flagged not built here.
 */
class CreateAccountUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository
) {
    data class Request(
        val companyId: CompanyId,
        val type: AccountType,
        val classification: AccountClassification?,
        val code: String,
        val name: String,
        val expenseClassification: ExpenseClassification? = null,
        val parentId: AccountId? = null,
        val cashBookKind: CashBookKind? = null
    )

    sealed class Result {
        data class Success(val account: Account) : Result()
        data object CompanyNotFound : Result()
        data class DuplicateCode(val code: String) : Result()
        data class InvalidAccount(val message: String?) : Result()
    }

    fun execute(request: Request): Result {
        companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound

        val existing = accountRepository.findAllByCompany(request.companyId)

        // A cash or bank account may be created with no code: a non-accountant should not invent one
        // (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB06; WEB's ask). GL takes the next free 10xx from 1010.
        // Every other account must carry its own code.
        val code = if (request.code.isNotBlank()) {
            request.code
        } else if (request.cashBookKind != null) {
            (FIRST_ASSIGNED_CASH_BOOK_CODE..LAST_ASSIGNED_CASH_BOOK_CODE).map { it.toString() }
                .firstOrNull { candidate -> existing.none { it.code == candidate } }
                ?: return Result.InvalidAccount("No free account code is left between $FIRST_ASSIGNED_CASH_BOOK_CODE and $LAST_ASSIGNED_CASH_BOOK_CODE; give this account a code")
        } else {
            return Result.InvalidAccount("code is required")
        }
        if (existing.any { it.code == code }) {
            return Result.DuplicateCode(code)
        }
        if (request.parentId != null && existing.none { it.id == request.parentId }) {
            return Result.InvalidAccount("parentId ${request.parentId.value} does not belong to this Company")
        }

        val account = try {
            Account.create(
                companyId = request.companyId,
                type = request.type,
                classification = request.classification,
                code = code,
                name = request.name,
                expenseClassification = request.expenseClassification,
                parentId = request.parentId,
                cashBookKind = request.cashBookKind
            )
        } catch (e: IllegalArgumentException) {
            return Result.InvalidAccount(e.message)
        }

        accountRepository.save(account)
        return Result.Success(account)
    }

    private companion object {
        const val FIRST_ASSIGNED_CASH_BOOK_CODE = 1010
        const val LAST_ASSIGNED_CASH_BOOK_CODE = 1099
    }
}
