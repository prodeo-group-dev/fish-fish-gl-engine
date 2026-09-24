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

/** Outcome of [AssessFixedAssetImpairmentUseCase.execute]. */
sealed class AssessFixedAssetImpairmentResult {
    data class Success(
        val journalEntry: JournalEntry,
        val fixedAsset: FixedAsset,
        val events: List<DomainEvent>
    ) : AssessFixedAssetImpairmentResult()
    data object FixedAssetNotFound : AssessFixedAssetImpairmentResult()
    /** [FixedAsset.assessImpairment] returned `null` - already disposed, or the target allowance is unchanged from the current one. */
    data object NoChangeNeeded : AssessFixedAssetImpairmentResult()
    data object PeriodNotFound : AssessFixedAssetImpairmentResult()
    data object PeriodNotOpen : AssessFixedAssetImpairmentResult()
    data class ImpairmentExpenseAccountNotFound(val accountId: AccountId) : AssessFixedAssetImpairmentResult()
    data class AccumulatedImpairmentAccountNotFound(val accountId: AccountId) : AssessFixedAssetImpairmentResult()
}

/**
 * IAS 36's recoverable-amount test for a single [FixedAsset] register
 * entry (docs/DDD_Design.md Section 2.8, `FixedAsset.assessImpairment`
 * added 2026-08-14) - a thin wrapper, same shape as
 * [RecordFixedAssetDepreciationUseCase]. [Request.recoverableAmount] is
 * caller-supplied (the higher of fair value less costs of disposal and
 * value in use) - this use case doesn't compute discounted cash flows
 * itself, matching [FixedAsset.assessImpairment]'s own "data, not code"
 * treatment.
 */
class AssessFixedAssetImpairmentUseCase(
    private val fixedAssetRepository: FixedAssetRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val fixedAssetId: FixedAssetId,
        val recoverableAmount: Money,
        val impairmentExpenseAccountId: AccountId,
        val accumulatedImpairmentAccountId: AccountId,
        val periodId: PeriodId,
        val date: LocalDate
    )

    fun execute(request: Request): AssessFixedAssetImpairmentResult {
        val fixedAsset = fixedAssetRepository.findById(request.fixedAssetId)
            ?: return AssessFixedAssetImpairmentResult.FixedAssetNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return AssessFixedAssetImpairmentResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return AssessFixedAssetImpairmentResult.PeriodNotOpen
        }

        // Cross-tenant isolation fix (2026-09-23, same gap/fix as PostJournalEntryUseCase):
        // each Account must belong to the FixedAsset's own Company, or it's treated as
        // AccountNotFound - never a distinct "forbidden" case, so this never confirms
        // another Company's Account exists.
        val impairmentExpenseAccount = accountRepository.findById(request.impairmentExpenseAccountId)
            ?.takeIf { it.companyId == fixedAsset.companyId }
            ?: return AssessFixedAssetImpairmentResult.ImpairmentExpenseAccountNotFound(request.impairmentExpenseAccountId)
        val accumulatedImpairmentAccount = accountRepository.findById(request.accumulatedImpairmentAccountId)
            ?.takeIf { it.companyId == fixedAsset.companyId }
            ?: return AssessFixedAssetImpairmentResult.AccumulatedImpairmentAccountNotFound(request.accumulatedImpairmentAccountId)

        val entry = fixedAsset.assessImpairment(
            request.recoverableAmount, impairmentExpenseAccount.id, accumulatedImpairmentAccount.id, request.periodId, request.date
        ) ?: return AssessFixedAssetImpairmentResult.NoChangeNeeded

        val posting = entry.post()
        check(posting.isValid) {
            "AssessFixedAssetImpairmentUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(impairmentExpenseAccount, accumulatedImpairmentAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)
        fixedAssetRepository.save(fixedAsset)

        return AssessFixedAssetImpairmentResult.Success(entry, fixedAsset, entry.pullDomainEvents())
    }
}
