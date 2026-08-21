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
 * Outcome of [UtilizeLeaveAccrualUseCase.execute] - a sealed `Result`,
 * same reasoning as [RemeasureLeaveAccrualResult].
 *
 * **`NonPositiveAmount`/`NothingToUtilize` are precise, pre-validated
 * branches, not one folded `InvalidUtilization` catch-all** (unlike
 * `PostInventoryReceiptResult.InvalidReceipt`/`PostInventoryIssueResult.InvalidIssue`,
 * which surface a domain `ValidationResult`'s error list directly).
 * `LeaveAccrual.utilizeLeave()` returns a bare `JournalEntry?`, not a
 * `ValidationResult`, so there's no error list to forward - instead this
 * use case pre-checks both of `utilizeLeave()`'s two null-return causes
 * itself (`request.amount` non-positive; `leaveAccrual.balance` already
 * zero) before ever calling it, the same "rule out every other cause
 * first" reasoning `PostSalesOrderUseCase` used for `InsufficientStock`,
 * but applied fully here since both causes are checkable in advance -
 * there's no residual "call it and see" case left afterward.
 */
sealed class UtilizeLeaveAccrualResult {
    data class Success(
        val leaveAccrual: LeaveAccrual,
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : UtilizeLeaveAccrualResult()
    data object LeaveAccrualNotFound : UtilizeLeaveAccrualResult()
    data object PeriodNotFound : UtilizeLeaveAccrualResult()
    data object PeriodNotOpen : UtilizeLeaveAccrualResult()
    data class CashAccountNotFound(val accountId: AccountId) : UtilizeLeaveAccrualResult()
    data class AccruedLeaveLiabilityAccountNotFound(val accountId: AccountId) : UtilizeLeaveAccrualResult()
    data object CurrencyMismatch : UtilizeLeaveAccrualResult()
    data object NonPositiveAmount : UtilizeLeaveAccrualResult()
    data object NothingToUtilize : UtilizeLeaveAccrualResult()
}

/**
 * Wraps `LeaveAccrual.utilizeLeave()` (docs/DDD_Design.md Section 2.7,
 * IAS 19) - the settlement counterpart [RemeasureLeaveAccrualUseCase]
 * deliberately doesn't cover, a separate use case for the same reason
 * `ReverseJournalEntryUseCase` is separate from `PostJournalEntryUseCase`
 * (see [RemeasureLeaveAccrualUseCase]'s own KDoc for the full reasoning).
 *
 * **Deliberately posts the resulting `JournalEntry`**, same reasoning as
 * every other "ecosystem"-style use case.
 *
 * **Pre-validates currency before calling `utilizeLeave()`**, same
 * reasoning as [RemeasureLeaveAccrualUseCase] - `Money`'s arithmetic
 * throws on a currency mismatch by design, and `Provision.utilize()` has
 * no internal currency check.
 *
 * **Reminder from `LeaveAccrual`'s own KDoc, worth repeating here since
 * this is the use case that would actually be miscalled**: avoiding
 * double-counting between this use case and `PostPayRunUseCase` is the
 * caller's responsibility. When accrued leave is taken, the employee is
 * still paid through the normal pay run - that portion of pay must be
 * charged here (drawing down the already-accrued liability), not folded
 * into `PayRun.totalWages`/`totalSalaries` again.
 */
class UtilizeLeaveAccrualUseCase(
    private val leaveAccrualRepository: LeaveAccrualRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val leaveAccrualId: LeaveAccrualId,
        val amount: Money,
        val cashAccountId: AccountId,
        val accruedLeaveLiabilityAccountId: AccountId,
        val periodId: PeriodId,
        val date: LocalDate
    )

    fun execute(request: Request): UtilizeLeaveAccrualResult {
        val leaveAccrual = leaveAccrualRepository.findById(request.leaveAccrualId)
            ?: return UtilizeLeaveAccrualResult.LeaveAccrualNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return UtilizeLeaveAccrualResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return UtilizeLeaveAccrualResult.PeriodNotOpen
        }

        val cashAccount = accountRepository.findById(request.cashAccountId)
            ?: return UtilizeLeaveAccrualResult.CashAccountNotFound(request.cashAccountId)
        val liabilityAccount = accountRepository.findById(request.accruedLeaveLiabilityAccountId)
            ?: return UtilizeLeaveAccrualResult.AccruedLeaveLiabilityAccountNotFound(request.accruedLeaveLiabilityAccountId)

        if (request.amount.currency != leaveAccrual.balance.currency) {
            return UtilizeLeaveAccrualResult.CurrencyMismatch
        }
        if (request.amount.amount.signum() <= 0) {
            return UtilizeLeaveAccrualResult.NonPositiveAmount
        }
        if (leaveAccrual.balance.amount.signum() <= 0) {
            return UtilizeLeaveAccrualResult.NothingToUtilize
        }

        val entry = checkNotNull(
            leaveAccrual.utilizeLeave(
                request.amount, request.cashAccountId, request.accruedLeaveLiabilityAccountId,
                request.periodId, request.date
            )
        ) {
            "UtilizeLeaveAccrualUseCase ruled out every known reason utilizeLeave() returns null " +
                "(non-positive amount, zero balance) before calling it, yet it returned null anyway"
        }

        val posting = entry.post()
        check(posting.isValid) {
            "UtilizeLeaveAccrualUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        cashAccount.recordActivity()
        accountRepository.save(cashAccount)
        liabilityAccount.recordActivity()
        accountRepository.save(liabilityAccount)

        leaveAccrualRepository.save(leaveAccrual)
        journalEntryRepository.save(entry)

        return UtilizeLeaveAccrualResult.Success(leaveAccrual, entry, entry.pullDomainEvents())
    }
}
