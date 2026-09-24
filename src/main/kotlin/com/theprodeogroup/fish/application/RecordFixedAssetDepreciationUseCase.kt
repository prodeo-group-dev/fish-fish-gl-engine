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
import java.time.LocalDate

/** Outcome of [RecordFixedAssetDepreciationUseCase.execute]. */
sealed class RecordFixedAssetDepreciationResult {
    data class Success(
        val journalEntry: JournalEntry,
        val fixedAsset: FixedAsset,
        val events: List<DomainEvent>
    ) : RecordFixedAssetDepreciationResult()
    data object FixedAssetNotFound : RecordFixedAssetDepreciationResult()
    /** [FixedAsset.recordDepreciation] returned `null` - already disposed, not depreciated (e.g. Land), or already fully depreciated. */
    data object NoChangeNeeded : RecordFixedAssetDepreciationResult()
    data object PeriodNotFound : RecordFixedAssetDepreciationResult()
    data object PeriodNotOpen : RecordFixedAssetDepreciationResult()
    data class DepreciationExpenseAccountNotFound(val accountId: AccountId) : RecordFixedAssetDepreciationResult()
    data class AccumulatedDepreciationAccountNotFound(val accountId: AccountId) : RecordFixedAssetDepreciationResult()
}

/**
 * Posts one year's straight-line depreciation for a single
 * [FixedAsset] register entry (docs/DDD_Design.md Section 2.8) -
 * a thin wrapper around [FixedAsset.recordDepreciation], same
 * find-validate-mutate-save shape as [RecordExpectedCreditLossUseCase].
 */
class RecordFixedAssetDepreciationUseCase(
    private val fixedAssetRepository: FixedAssetRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val fixedAssetId: FixedAssetId,
        val depreciationExpenseAccountId: AccountId,
        val accumulatedDepreciationAccountId: AccountId,
        val periodId: PeriodId,
        val date: LocalDate
    )

    fun execute(request: Request): RecordFixedAssetDepreciationResult {
        val fixedAsset = fixedAssetRepository.findById(request.fixedAssetId)
            ?: return RecordFixedAssetDepreciationResult.FixedAssetNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return RecordFixedAssetDepreciationResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordFixedAssetDepreciationResult.PeriodNotOpen
        }

        // Cross-tenant isolation fix (2026-09-23, same gap/fix as PostJournalEntryUseCase):
        // each Account must belong to the FixedAsset's own Company, or it's treated as
        // AccountNotFound - never a distinct "forbidden" case, so this never confirms
        // another Company's Account exists.
        val depreciationExpenseAccount = accountRepository.findById(request.depreciationExpenseAccountId)
            ?.takeIf { it.companyId == fixedAsset.companyId }
            ?: return RecordFixedAssetDepreciationResult.DepreciationExpenseAccountNotFound(request.depreciationExpenseAccountId)
        val accumulatedDepreciationAccount = accountRepository.findById(request.accumulatedDepreciationAccountId)
            ?.takeIf { it.companyId == fixedAsset.companyId }
            ?: return RecordFixedAssetDepreciationResult.AccumulatedDepreciationAccountNotFound(request.accumulatedDepreciationAccountId)

        val entry = fixedAsset.recordDepreciation(
            depreciationExpenseAccount.id, accumulatedDepreciationAccount.id, request.periodId, request.date
        ) ?: return RecordFixedAssetDepreciationResult.NoChangeNeeded

        val posting = entry.post()
        check(posting.isValid) {
            "RecordFixedAssetDepreciationUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(depreciationExpenseAccount, accumulatedDepreciationAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)
        fixedAssetRepository.save(fixedAsset)

        return RecordFixedAssetDepreciationResult.Success(entry, fixedAsset, entry.pullDomainEvents())
    }
}
