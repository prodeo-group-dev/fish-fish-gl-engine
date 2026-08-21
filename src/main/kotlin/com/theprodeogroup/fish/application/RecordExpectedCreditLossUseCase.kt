package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.sales.AccountsReceivableAging
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outcome of [RecordExpectedCreditLossUseCase.execute] - a sealed
 * `Result`, same reasoning as every other multi-reason posting use
 * case this codebase has built. [NoChangeNeeded] mirrors
 * `RecordFXRevaluationResult`/`RemeasureLeaveAccrualResult`'s own
 * "nothing changed, nothing to post" precedent.
 */
sealed class RecordExpectedCreditLossResult {
    data class Success(
        val journalEntry: JournalEntry,
        val newAllowance: Money,
        val events: List<DomainEvent>
    ) : RecordExpectedCreditLossResult()
    data object NoChangeNeeded : RecordExpectedCreditLossResult()
    data object PeriodNotFound : RecordExpectedCreditLossResult()
    data object PeriodNotOpen : RecordExpectedCreditLossResult()
    data class ExpenseAccountNotFound(val accountId: AccountId) : RecordExpectedCreditLossResult()
    data class AllowanceAccountNotFound(val accountId: AccountId) : RecordExpectedCreditLossResult()
}

/**
 * IFRS 9's simplified approach for trade receivables
 * (docs/Sales_Processing_Requirements_Specification.md Section 5.2) -
 * **reconnects** ECL assessment to the new "no owning aggregate"
 * architecture. `Customer.assessExpectedCreditLoss()` (the pre-existing
 * logic this ports) caps its target allowance against `Customer.balance`
 * and reads/writes `Customer.allowanceForExpectedCreditLoss` - both
 * only stay current via the *old* `SalesOrder.deliverLine()`/
 * `Customer.receivePayment()` pathway. Sales/collections posted through
 * `RecordSaleUseCase`/`RecordCollectionUseCase` never touch `Customer`
 * at all, so for any customer using the new pathway that capping was
 * silently wrong, not just unbuilt.
 *
 * This use case caps against [AccountsReceivableAging.totalOutstanding]
 * instead - already Ledger-derived from posted `JournalLine`s tagged
 * `DimensionType.CUSTOMER` (confirmed by direct code inventory: `AccountsReceivableAging.of()`
 * reads only posted `JournalEntry` data, has zero dependency on
 * `Customer`), so it's correct regardless of which pathway posted the
 * underlying sales/receipts. [Request.currentAllowance] is caller-
 * supplied rather than read from `Customer` - the same "no persisted
 * position, caller carries the last value forward" shape
 * `RecordFXRevaluationUseCase` already established for period-end FX
 * revaluation, an analogous problem (no dedicated Provision-style
 * aggregate for this exists yet either).
 */
class RecordExpectedCreditLossUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val aging: AccountsReceivableAging,
        val lossRates: Map<AgingBucketLabel, BigDecimal>,
        val currentAllowance: Money,
        val expenseAccountId: AccountId,
        val allowanceAccountId: AccountId,
        val description: String? = null
    )

    fun execute(request: Request): RecordExpectedCreditLossResult {
        val period = periodRepository.findById(request.periodId)
            ?: return RecordExpectedCreditLossResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordExpectedCreditLossResult.PeriodNotOpen
        }

        val expenseAccount = accountRepository.findById(request.expenseAccountId)
            ?: return RecordExpectedCreditLossResult.ExpenseAccountNotFound(request.expenseAccountId)
        val allowanceAccount = accountRepository.findById(request.allowanceAccountId)
            ?: return RecordExpectedCreditLossResult.AllowanceAccountNotFound(request.allowanceAccountId)

        val zero = Money(BigDecimal.ZERO, request.aging.currency)
        val targetAllowance = request.aging.buckets.fold(zero) { sum, bucket ->
            sum + (bucket.amount * (request.lossRates[bucket.label] ?: BigDecimal.ZERO))
        }
        val cappedTarget = if (targetAllowance > request.aging.totalOutstanding) request.aging.totalOutstanding else targetAllowance

        val delta = cappedTarget - request.currentAllowance
        if (delta.amount.signum() == 0) {
            return RecordExpectedCreditLossResult.NoChangeNeeded
        }

        val lines = if (delta.amount.signum() > 0) {
            listOf(
                JournalLine(expenseAccount.id, delta, TransactionSide.DEBIT),
                JournalLine(allowanceAccount.id, delta, TransactionSide.CREDIT)
            )
        } else {
            val reversalAmount = zero - delta
            listOf(
                JournalLine(allowanceAccount.id, reversalAmount, TransactionSide.DEBIT),
                JournalLine(expenseAccount.id, reversalAmount, TransactionSide.CREDIT)
            )
        }
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.SYSTEM, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordExpectedCreditLossUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(expenseAccount, allowanceAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordExpectedCreditLossResult.Success(entry, cappedTarget, entry.pullDomainEvents())
    }
}
