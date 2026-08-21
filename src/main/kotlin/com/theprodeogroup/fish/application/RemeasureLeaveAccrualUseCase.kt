package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import java.time.LocalDate

/**
 * Outcome of [RemeasureLeaveAccrualUseCase.execute] - a sealed `Result`,
 * same reasoning as every other multi-reason posting use case this
 * codebase has built.
 *
 * **`NoChangeNeeded` is a distinct branch, not folded into `Success`
 * with a nullable `journalEntry` - a deliberate choice, matching how
 * `LeaveAccrual.remeasure()` (via `Provision.remeasure()`) itself treats
 * a zero delta as a legitimate "nothing to do" outcome, not an error.**
 * Keeping it a separate `Result` case lets a caller distinguish "nothing
 * changed" from "something posted" with a plain `when`, without having
 * to null-check a field on `Success`.
 */
sealed class RemeasureLeaveAccrualResult {
    data class Success(
        val leaveAccrual: LeaveAccrual,
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RemeasureLeaveAccrualResult()
    data object NoChangeNeeded : RemeasureLeaveAccrualResult()
    data object LeaveAccrualNotFound : RemeasureLeaveAccrualResult()
    data object PeriodNotFound : RemeasureLeaveAccrualResult()
    data object PeriodNotOpen : RemeasureLeaveAccrualResult()
    data class LeaveExpenseAccountNotFound(val accountId: AccountId) : RemeasureLeaveAccrualResult()
    data class AccruedLeaveLiabilityAccountNotFound(val accountId: AccountId) : RemeasureLeaveAccrualResult()
    data object CurrencyMismatch : RemeasureLeaveAccrualResult()
}

/**
 * Wraps `LeaveAccrual.remeasure()` (docs/DDD_Design.md Section 2.7,
 * IAS 19) - the first application-layer entry point for `LeaveAccrual`,
 * built alongside its first repository (`LeaveAccrualRepository`,
 * Section 10.18) since neither existed before this use case did.
 *
 * **A separate use case from [UtilizeLeaveAccrualUseCase], not one use
 * case with two operations - matching `PostJournalEntryUseCase`/
 * `ReverseJournalEntryUseCase`'s precedent** (Sections 10.8/10.12): two
 * genuinely distinct domain operations on the same aggregate get two
 * use cases, not a combined `Request` with an operation-type flag.
 * `remeasure()` (a periodic re-estimate) and `utilizeLeave()` (settling
 * leave actually taken) have different callers, different cadences, and
 * different failure shapes - the same reasoning that already separated
 * posting from reversal.
 *
 * **Deliberately posts the resulting `JournalEntry`** (`.post()`, not
 * just `remeasure()`), same reasoning as every other "ecosystem"-style
 * use case - `remeasure()` alone only ever produces a `Draft` entry via
 * `JournalEntry.create()`.
 *
 * **Pre-validates currency before calling `remeasure()`, to avoid an
 * uncaught exception - `Money`'s arithmetic throws on a currency
 * mismatch by design** (its own KDoc: "Arithmetic and comparison reject
 * mixing currencies by throwing, rather than silently producing a
 * meaningless result"), and `Provision.remeasure()` has no internal
 * currency check the way `StockItem.recordReceipt()` does. Checking
 * `request.targetAmount.currency` against the `LeaveAccrual`'s own
 * `balance.currency` first turns what would otherwise be an unhandled
 * `IllegalArgumentException` into an honest `Result` branch, matching
 * every other use case's discipline of never letting a domain-layer
 * `require()` propagate uncaught.
 */
class RemeasureLeaveAccrualUseCase(
    private val leaveAccrualRepository: LeaveAccrualRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val leaveAccrualId: LeaveAccrualId,
        val targetAmount: Money,
        val leaveExpenseAccountId: AccountId,
        val accruedLeaveLiabilityAccountId: AccountId,
        val periodId: PeriodId,
        val date: LocalDate
    )

    fun execute(request: Request): RemeasureLeaveAccrualResult {
        val leaveAccrual = leaveAccrualRepository.findById(request.leaveAccrualId)
            ?: return RemeasureLeaveAccrualResult.LeaveAccrualNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return RemeasureLeaveAccrualResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RemeasureLeaveAccrualResult.PeriodNotOpen
        }

        val leaveExpenseAccount = accountRepository.findById(request.leaveExpenseAccountId)
            ?: return RemeasureLeaveAccrualResult.LeaveExpenseAccountNotFound(request.leaveExpenseAccountId)
        val liabilityAccount = accountRepository.findById(request.accruedLeaveLiabilityAccountId)
            ?: return RemeasureLeaveAccrualResult.AccruedLeaveLiabilityAccountNotFound(request.accruedLeaveLiabilityAccountId)

        if (request.targetAmount.currency != leaveAccrual.balance.currency) {
            return RemeasureLeaveAccrualResult.CurrencyMismatch
        }

        val entry = leaveAccrual.remeasure(
            request.targetAmount, request.leaveExpenseAccountId, request.accruedLeaveLiabilityAccountId,
            request.periodId, request.date
        ) ?: return RemeasureLeaveAccrualResult.NoChangeNeeded

        val posting = entry.post()
        check(posting.isValid) {
            "RemeasureLeaveAccrualUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        leaveExpenseAccount.recordActivity()
        accountRepository.save(leaveExpenseAccount)
        liabilityAccount.recordActivity()
        accountRepository.save(liabilityAccount)

        leaveAccrualRepository.save(leaveAccrual)
        journalEntryRepository.save(entry)

        return RemeasureLeaveAccrualResult.Success(leaveAccrual, entry, entry.pullDomainEvents())
    }
}
