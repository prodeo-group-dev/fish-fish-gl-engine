package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetId
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetRepository
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.common.Money
import java.time.LocalDate

/** Outcome of [DisposeFixedAssetUseCase.execute]. */
sealed class DisposeFixedAssetResult {
    data class Success(
        val journalEntry: JournalEntry,
        val fixedAsset: FixedAsset,
        val events: List<DomainEvent>
    ) : DisposeFixedAssetResult()
    data object FixedAssetNotFound : DisposeFixedAssetResult()
    data object AlreadyDisposed : DisposeFixedAssetResult()
    /** [FixedAsset.dispose]'s own guard: a positive [FixedAsset.accumulatedImpairmentLoss] requires [Request.accumulatedImpairmentAccountId]. */
    data object AccumulatedImpairmentAccountRequired : DisposeFixedAssetResult()
    data object PeriodNotFound : DisposeFixedAssetResult()
    data object PeriodNotOpen : DisposeFixedAssetResult()
    data class CashAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
    data class FixedAssetAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
    data class AccumulatedDepreciationAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
    data class SaleOfFixedAssetAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
    data class AccumulatedImpairmentAccountNotFound(val accountId: AccountId) : DisposeFixedAssetResult()
}

/**
 * Disposes of a single [FixedAsset] register entry via the Asset
 * Disposal Account method (docs/DDD_Design.md Section 2.8, confirmed
 * 2026-08-12) - a thin wrapper around [FixedAsset.dispose], same shape
 * as [RecordFixedAssetDepreciationUseCase]. Gain/loss is derived by the
 * ledger (whatever balance remains on [saleOfFixedAssetAccountId] after
 * all legs post), **not** computed directly into separate gain/loss
 * accounts - see [FixedAsset.dispose]'s own KDoc for why that's the
 * deliberate design (confirmed again, not changed, 2026-09-03).
 */
class DisposeFixedAssetUseCase(
    private val fixedAssetRepository: FixedAssetRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val fixedAssetId: FixedAssetId,
        val proceeds: Money,
        val cashAccountId: AccountId,
        val fixedAssetAccountId: AccountId,
        val accumulatedDepreciationAccountId: AccountId,
        val saleOfFixedAssetAccountId: AccountId,
        val periodId: PeriodId,
        val date: LocalDate,
        val accumulatedImpairmentAccountId: AccountId? = null
    )

    fun execute(request: Request): DisposeFixedAssetResult {
        val fixedAsset = fixedAssetRepository.findById(request.fixedAssetId)
            ?: return DisposeFixedAssetResult.FixedAssetNotFound
        if (fixedAsset.isDisposed) {
            return DisposeFixedAssetResult.AlreadyDisposed
        }
        if (fixedAsset.accumulatedImpairmentLoss.amount.signum() > 0 && request.accumulatedImpairmentAccountId == null) {
            return DisposeFixedAssetResult.AccumulatedImpairmentAccountRequired
        }

        val period = periodRepository.findById(request.periodId)
            ?: return DisposeFixedAssetResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return DisposeFixedAssetResult.PeriodNotOpen
        }

        val cashAccount = accountRepository.findById(request.cashAccountId)
            ?: return DisposeFixedAssetResult.CashAccountNotFound(request.cashAccountId)
        val fixedAssetAccount = accountRepository.findById(request.fixedAssetAccountId)
            ?: return DisposeFixedAssetResult.FixedAssetAccountNotFound(request.fixedAssetAccountId)
        val accumulatedDepreciationAccount = accountRepository.findById(request.accumulatedDepreciationAccountId)
            ?: return DisposeFixedAssetResult.AccumulatedDepreciationAccountNotFound(request.accumulatedDepreciationAccountId)
        val saleOfFixedAssetAccount = accountRepository.findById(request.saleOfFixedAssetAccountId)
            ?: return DisposeFixedAssetResult.SaleOfFixedAssetAccountNotFound(request.saleOfFixedAssetAccountId)
        val accumulatedImpairmentAccount = request.accumulatedImpairmentAccountId?.let { accountId ->
            accountRepository.findById(accountId) ?: return DisposeFixedAssetResult.AccumulatedImpairmentAccountNotFound(accountId)
        }

        val entry = fixedAsset.dispose(
            proceeds = request.proceeds,
            cashAccountId = cashAccount.id,
            fixedAssetAccountId = fixedAssetAccount.id,
            accumulatedDepreciationAccountId = accumulatedDepreciationAccount.id,
            saleOfFixedAssetAccountId = saleOfFixedAssetAccount.id,
            periodId = request.periodId,
            date = request.date,
            accumulatedImpairmentAccountId = accumulatedImpairmentAccount?.id
        ) ?: return DisposeFixedAssetResult.AlreadyDisposed

        val posting = entry.post()
        check(posting.isValid) {
            "DisposeFixedAssetUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOfNotNull(
            cashAccount, fixedAssetAccount, accumulatedDepreciationAccount, saleOfFixedAssetAccount, accumulatedImpairmentAccount
        )
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)
        fixedAssetRepository.save(fixedAsset)

        return DisposeFixedAssetResult.Success(entry, fixedAsset, entry.pullDomainEvents())
    }
}
