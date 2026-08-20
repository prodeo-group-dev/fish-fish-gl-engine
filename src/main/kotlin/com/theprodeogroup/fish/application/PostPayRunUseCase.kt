package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.payroll.PayRun
import com.theprodeogroup.fish.domain.payroll.PayRunId
import com.theprodeogroup.fish.domain.payroll.PayRunRepository

/**
 * Outcome of [PostPayRunUseCase.execute] - a sealed `Result`, same
 * reasoning as every other multi-reason posting use case this codebase
 * has built. The smallest failure surface of the "ecosystem" use cases
 * so far, matching `PayRun.post()`'s own simplicity - `PayRun` has no
 * state machine and `post()` never returns `null` or fails internally,
 * so every branch here comes directly from raw [Request] input, not
 * from any internal invariant `PayRun.post()` itself could violate.
 */
sealed class PostPayRunResult {
    data class Success(
        val payRun: PayRun,
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : PostPayRunResult()
    data object PayRunNotFound : PostPayRunResult()
    data object PeriodNotFound : PostPayRunResult()
    data object PeriodNotOpen : PostPayRunResult()
    data class WagesExpenseAccountNotFound(val accountId: AccountId) : PostPayRunResult()
    data class SalariesExpenseAccountNotFound(val accountId: AccountId) : PostPayRunResult()
    data class CashAccountNotFound(val accountId: AccountId) : PostPayRunResult()
}

/**
 * Wraps `PayRun.post()` (docs/DDD_Design.md Section 2.7) - the fourth
 * and final "ecosystem" increment finally gets an application-layer
 * entry point, completing the same coverage `PostPurchaseOrderUseCase`/
 * `PostSalesOrderUseCase`/`PostInventoryReceiptUseCase`/
 * `PostInventoryIssueUseCase` already gave the other three.
 *
 * **Deliberately posts the resulting `JournalEntry`** (`.post()`, not
 * just `PayRun.post()`), same reasoning as every prior "ecosystem" use
 * case - `PayRun.post()` itself only ever produces a `Draft` entry via
 * `JournalEntry.create()`.
 *
 * **Never saves `PayRun` itself - a genuine, reasoned omission, not an
 * oversight.** Unlike `PurchaseOrder`/`SalesOrder`/`StockItem`, `PayRun`
 * has no mutable state at all (every field is `val`) - `post()` reads
 * `totalWages`/`totalSalaries` to build the `JournalEntry` but changes
 * nothing about `PayRun` itself, so there is nothing new to persist.
 * `PayRunRepository` is still a dependency (needed for `findById()`),
 * just never called with `save()`.
 *
 * **No "already posted" guard, matching this session's established
 * precedent for aggregates with no state machine of their own.**
 * `PurchaseOrder`/`SalesOrder` prevent double-posting via their own
 * `DRAFT`/`SENT`/computed-`status` state; `PayRun` (like `StockItem`)
 * has none, and this use case doesn't invent one on `PayRun`'s behalf -
 * the same restraint already applied to `PostInventoryReceiptUseCase`/
 * `PostInventoryIssueUseCase`, which don't guard against calling
 * `StockItem.recordReceipt()`/`recordIssue()` twice either.
 *
 * **Only marks/saves the `Account`s `PayRun.post()` actually touches**:
 * the wages/salaries side is conditionally omitted when zero (`PayRun`'s
 * own "nothing to post" precedent), so this use case mirrors that -
 * an unused expense `Account` never gets `recordActivity()`'d.
 */
class PostPayRunUseCase(
    private val payRunRepository: PayRunRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val payRunId: PayRunId,
        val periodId: PeriodId,
        val wagesExpenseAccountId: AccountId,
        val salariesExpenseAccountId: AccountId,
        val cashAccountId: AccountId
    )

    fun execute(request: Request): PostPayRunResult {
        val payRun = payRunRepository.findById(request.payRunId)
            ?: return PostPayRunResult.PayRunNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return PostPayRunResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return PostPayRunResult.PeriodNotOpen
        }

        val wagesAccount = accountRepository.findById(request.wagesExpenseAccountId)
            ?: return PostPayRunResult.WagesExpenseAccountNotFound(request.wagesExpenseAccountId)
        val salariesAccount = accountRepository.findById(request.salariesExpenseAccountId)
            ?: return PostPayRunResult.SalariesExpenseAccountNotFound(request.salariesExpenseAccountId)
        val cashAccount = accountRepository.findById(request.cashAccountId)
            ?: return PostPayRunResult.CashAccountNotFound(request.cashAccountId)

        val entry = payRun.post(
            request.wagesExpenseAccountId, request.salariesExpenseAccountId,
            request.cashAccountId, request.periodId
        )
        val posting = entry.post()
        check(posting.isValid) {
            "PostPayRunUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts = mutableListOf<Account>()
        if (payRun.totalWages.amount.signum() > 0) touchedAccounts.add(wagesAccount)
        if (payRun.totalSalaries.amount.signum() > 0) touchedAccounts.add(salariesAccount)
        touchedAccounts.add(cashAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return PostPayRunResult.Success(payRun, entry, entry.pullDomainEvents())
    }
}
