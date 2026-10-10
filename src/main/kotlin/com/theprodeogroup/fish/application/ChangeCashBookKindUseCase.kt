package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * `PUT /companies/{companyId}/accounts/{accountId}/cash-book-kind` (docs/GL_Cash_And_Bank_Books_SRS.md,
 * FR-CB02): flags an existing ASSET account as CASH or BANK, or clears the flag. This is how a Company
 * whose only account `1000` is really its bank makes it reconcilable.
 *
 * It changes what the account can do, never a balance. An account of another Company is reported as not
 * found (never a distinct "forbidden", so this never confirms another Company's account exists).
 * A BANK account that already has a reconciliation cannot be cleared or changed to CASH: the reconciliation
 * history would then belong to an account that no longer has the feature.
 */
class ChangeCashBookKindUseCase(
    private val accountRepository: AccountRepository,
    private val bankReconciliationRepository: BankReconciliationRepository
) {
    sealed class Result {
        data class Success(val account: Account) : Result()
        data object AccountNotFound : Result()
        data class NotAnAssetAccount(val message: String?) : Result()
        data object ReconciliationsExist : Result()

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
        if (account.cashBookKind == CashBookKind.BANK && kind != CashBookKind.BANK) {
            val hasReconciliations = bankReconciliationRepository.findAllByCompany(companyId, emptyList(), accountId).isNotEmpty()
            if (hasReconciliations) return Result.ReconciliationsExist
        }

        val outcome = account.changeCashBookKind(kind)
        if (!outcome.isValid) return Result.NotAnAssetAccount(outcome.errors.firstOrNull())
        accountRepository.save(account)
        return Result.Success(account)
    }
}
