package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.FXRate
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import java.time.LocalDate

/**
 * Outcome of [RecordFXRevaluationUseCase.execute] - a sealed `Result`,
 * same reasoning as every other multi-reason posting use case this
 * codebase has built. [NoChangeNeeded] mirrors
 * `RemeasureLeaveAccrualResult`/`FixedAsset.recordDepreciation()`'s own
 * "nothing changed, nothing to post" precedent.
 */
sealed class RecordFXRevaluationResult {
    data class Success(
        val journalEntry: JournalEntry,
        val delta: Money,
        val events: List<DomainEvent>
    ) : RecordFXRevaluationResult()
    data object NoChangeNeeded : RecordFXRevaluationResult()
    data object PeriodNotFound : RecordFXRevaluationResult()
    data object PeriodNotOpen : RecordFXRevaluationResult()
    data class ReceivableAccountNotFound(val accountId: AccountId) : RecordFXRevaluationResult()
    data class FxGainLossAccountNotFound(val accountId: AccountId) : RecordFXRevaluationResult()
}

/**
 * IAS 21 period-end foreign-currency receivable revaluation
 * (docs/Sales_Processing_Requirements_Specification.md Section 6/7.1) -
 * the same target-and-delta remeasurement shape
 * `Customer.assessExpectedCreditLoss()` already established: compute
 * the new home-currency value at [Request.currentRate], post only the
 * **delta** against [Request.currentlyRecordedHomeValue], not the full
 * new value.
 *
 * A thin, no-owning-aggregate use case (the same "calling system
 * computes the number" shape as `RecordSaleUseCase`/`RecordCollectionUseCase`)
 * - there's no persisted "open FX position" concept in this repo yet,
 * so the caller supplies both the outstanding foreign-currency amount
 * and what's currently recorded in home currency for it. Doesn't
 * pre-validate that [Request.currentRate]'s currencies match
 * [Request.foreignCurrencyAmount]/[Request.currentlyRecordedHomeValue]
 * - `Money`'s/`FXRate`'s own guards throw on mismatch, the same
 * precedent every other "ecosystem" use case in this codebase follows.
 */
class RecordFXRevaluationUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val receivableAccountId: AccountId,
        val fxGainLossAccountId: AccountId,
        val foreignCurrencyAmount: Money,
        val currentlyRecordedHomeValue: Money,
        val currentRate: FXRate,
        val description: String? = null
    )

    fun execute(request: Request): RecordFXRevaluationResult {
        val period = periodRepository.findById(request.periodId)
            ?: return RecordFXRevaluationResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordFXRevaluationResult.PeriodNotOpen
        }

        val receivableAccount = accountRepository.findById(request.receivableAccountId)
            ?: return RecordFXRevaluationResult.ReceivableAccountNotFound(request.receivableAccountId)
        val fxGainLossAccount = accountRepository.findById(request.fxGainLossAccountId)
            ?: return RecordFXRevaluationResult.FxGainLossAccountNotFound(request.fxGainLossAccountId)

        val newHomeValue = request.currentRate.convert(request.foreignCurrencyAmount)
        val delta = newHomeValue - request.currentlyRecordedHomeValue
        if (delta.amount.signum() == 0) {
            return RecordFXRevaluationResult.NoChangeNeeded
        }

        val lines = if (delta.amount.signum() > 0) {
            // Foreign currency strengthened - the receivable's home-currency value increased.
            listOf(
                JournalLine(receivableAccount.id, delta, TransactionSide.DEBIT),
                JournalLine(fxGainLossAccount.id, delta, TransactionSide.CREDIT)
            )
        } else {
            // Foreign currency weakened - the receivable's home-currency value decreased.
            val lossAmount = request.currentlyRecordedHomeValue - newHomeValue
            listOf(
                JournalLine(fxGainLossAccount.id, lossAmount, TransactionSide.DEBIT),
                JournalLine(receivableAccount.id, lossAmount, TransactionSide.CREDIT)
            )
        }
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.SYSTEM, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordFXRevaluationUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(receivableAccount, fxGainLossAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordFXRevaluationResult.Success(entry, delta, entry.pullDomainEvents())
    }
}
