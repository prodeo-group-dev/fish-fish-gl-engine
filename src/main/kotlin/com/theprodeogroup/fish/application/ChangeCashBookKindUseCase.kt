package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * `PUT /companies/{companyId}/accounts/{accountId}/cash-book-kind` (docs/GL_Cash_And_Bank_Books_SRS.md,
 * FR-CB02): flags an existing ASSET account as CASH or BANK, or clears the flag. This is how a Company
 * whose only account `1000` is really its bank makes it reconcilable.
 *
 * It changes what the account can do, never a balance. An account of another Company is reported as not
 * found (never a distinct "forbidden", so this never confirms another Company's account exists).
 * GL-T-18 (Femi, 2026-10-23: "impossible.. Must not be done"): an account that is already a CASH book cannot be changed to BANK,
 * nor a BANK account to CASH ([Result.KindFixed]); the way out of a wrongly set up book is a new book of the right kind. Setting a kind
 * from none, and repeating the current kind, are allowed.
 */
class ChangeCashBookKindUseCase(
    private val accountRepository: AccountRepository
) {
    sealed class Result {
        data class Success(val account: Account) : Result()
        data object AccountNotFound : Result()
        data class NotAnAssetAccount(val message: String?) : Result()

        /** A cash book stays a cash book and a bank account stays a bank account (GL-T-18). */
        data object KindFixed : Result()

        /** Account `1000` is the Cash Book in every Company (SRS decision D4); its kind is CASH and cannot be changed. */
        data object PrimeCashBookKindFixed : Result()
    }

    fun execute(companyId: CompanyId, accountId: AccountId, kind: CashBookKind?): Result {
        val account = accountRepository.findById(accountId)
            ?.takeIf { it.companyId == companyId }
            ?: return Result.AccountNotFound

        if (account.code == ListCashBooksUseCase.DEFAULT_CASH_BOOK_CODE && kind != CashBookKind.CASH) {
            return Result.PrimeCashBookKindFixed
        }
        val current = account.cashBookKind
        if (current != null && kind != null && kind != current) return Result.KindFixed

        val outcome = account.changeCashBookKind(kind)
        if (!outcome.isValid) return Result.NotAnAssetAccount(outcome.errors.firstOrNull())
        accountRepository.save(account)
        return Result.Success(account)
    }
}
