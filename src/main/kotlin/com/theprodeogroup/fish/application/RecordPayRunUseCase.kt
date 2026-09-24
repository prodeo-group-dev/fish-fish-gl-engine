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
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.common.Money
import java.time.LocalDate

/**
 * Outcome of [RecordPayRunUseCase.execute] - a sealed `Result`, same
 * reasoning as [RecordSaleResult]/[RecordCollectionResult].
 */
sealed class RecordPayRunResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordPayRunResult()
    data object InvalidAmounts : RecordPayRunResult()
    data object PeriodNotFound : RecordPayRunResult()
    data object PeriodNotOpen : RecordPayRunResult()
    data class WagesExpenseAccountNotFound(val accountId: AccountId) : RecordPayRunResult()
    data class SalariesExpenseAccountNotFound(val accountId: AccountId) : RecordPayRunResult()
    data class CashAccountNotFound(val accountId: AccountId) : RecordPayRunResult()
}

/**
 * The *Record pay run* thin posting interface - the same fix
 * `RecordSaleUseCase`/`RecordCollectionUseCase` already gave Sales
 * Order Processing, applied to the identical blocker HR/Payroll hits:
 * `PostPayRunUseCase` requires an already-persisted `PayRun` looked up
 * by ID, but no route exists anywhere to create one, and HR/Payroll
 * has no reason to ask the GL Engine to persist a `PayRun` it computes
 * fresh every pay period.
 *
 * **Reuses `PayRun.create()`/`PayRun.post()` directly rather than
 * rebuilding the Dr/Cr construction inline** - unlike Sales Order
 * Processing's revenue-recognition timing, which genuinely diverges
 * from `SalesOrder.deliverLine()`'s bundled posting, HR/Payroll's
 * `PayrollBatch` computes exactly the two totals `PayRun.create()`
 * already expects (`docs/HR_Payroll_DDD_Design.md` Section 3.4). No
 * reason to duplicate `PayRun.post()`'s omit-the-zero-side logic when
 * the shapes already match.
 *
 * **Still never persists `PayRun`** - same reasoning `PostPayRunUseCase`
 * already documents (every field is `val`, `post()` changes nothing
 * about `PayRun` itself). The `PayRun` this constructs exists only for
 * the duration of this call; `PayRunRepository` isn't a dependency
 * here at all, unlike `PostPayRunUseCase`.
 */
class RecordPayRunUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val companyId: CompanyId,
        val periodId: PeriodId,
        val date: LocalDate,
        val totalWages: Money,
        val totalSalaries: Money,
        val wagesExpenseAccountId: AccountId,
        val salariesExpenseAccountId: AccountId,
        val cashAccountId: AccountId
    )

    fun execute(request: Request): RecordPayRunResult {
        if (request.totalWages.currency != request.totalSalaries.currency) {
            return RecordPayRunResult.InvalidAmounts
        }
        if (request.totalWages.amount.signum() < 0 || request.totalSalaries.amount.signum() < 0) {
            return RecordPayRunResult.InvalidAmounts
        }
        if (request.totalWages.amount.signum() == 0 && request.totalSalaries.amount.signum() == 0) {
            return RecordPayRunResult.InvalidAmounts
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordPayRunResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordPayRunResult.PeriodNotOpen
        }

        // Cross-tenant isolation fix (2026-09-23, same gap/fix as PostJournalEntryUseCase):
        // each Account must belong to this Period's own Company, or it's treated as
        // AccountNotFound - never a distinct "forbidden" case, so this never confirms
        // another Company's Account exists.
        val wagesAccount = accountRepository.findById(request.wagesExpenseAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordPayRunResult.WagesExpenseAccountNotFound(request.wagesExpenseAccountId)
        val salariesAccount = accountRepository.findById(request.salariesExpenseAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordPayRunResult.SalariesExpenseAccountNotFound(request.salariesExpenseAccountId)
        val cashAccount = accountRepository.findById(request.cashAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordPayRunResult.CashAccountNotFound(request.cashAccountId)

        val payRun = PayRun.create(request.companyId, request.date, request.totalWages, request.totalSalaries)
        val entry = payRun.post(
            request.wagesExpenseAccountId, request.salariesExpenseAccountId,
            request.cashAccountId, request.periodId
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordPayRunUseCase built a JournalEntry that failed its own post() precondition: " +
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

        return RecordPayRunResult.Success(entry, entry.pullDomainEvents())
    }
}
