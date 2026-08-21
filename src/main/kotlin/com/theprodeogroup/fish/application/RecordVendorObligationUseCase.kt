package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import java.time.LocalDate

/**
 * Outcome of [RecordVendorObligationUseCase.execute] - a sealed
 * `Result`, same reasoning as every other multi-reason posting use case
 * this codebase has built, mirroring [RecordSaleResult] exactly.
 */
sealed class RecordVendorObligationResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordVendorObligationResult()
    data object InvalidAmount : RecordVendorObligationResult()
    data object PeriodNotFound : RecordVendorObligationResult()
    data object PeriodNotOpen : RecordVendorObligationResult()
    data class ExpenseOrAssetAccountNotFound(val accountId: AccountId) : RecordVendorObligationResult()
    data class ApControlAccountNotFound(val accountId: AccountId) : RecordVendorObligationResult()
}

/**
 * The *Record vendor obligation* thin posting interface
 * (docs/Purchase_Order_Processing_DDD_Design.md Section 0/4) - the
 * Purchasing mirror of [RecordSaleUseCase]. Dr caller-specified
 * Expense/Asset account, Cr AP Control account, tagged
 * `DimensionType.VENDOR` with a caller-supplied [CreditorId]. Called by
 * `fish-purchase-order-processing` (POP) once its own `PurchaseOrder`
 * records a successful three-way match (UC-PO5) - **not** at PO-send,
 * a deliberate, flagged departure from this repo's current
 * `PurchaseOrder.send()`/`PostPurchaseOrderUseCase`, which still
 * recognize AP immediately at send. `domain.purchasing.Creditor`/
 * `PurchaseOrder` still exist here unchanged (design doc Section 6 -
 * nothing in `fish-fish-gl-engine` is deleted by this), this use case
 * is additive, coexisting with the old `PostPurchaseOrderUseCase`.
 *
 * **Single Expense/Asset account, not a list** - POP's own
 * `PurchaseOrderLine` KDoc is explicit that "account routing happens at
 * the GL Engine's thin *Record vendor obligation* call... and only
 * once, at three-way match, not per line at PO creation," so this
 * mirrors [RecordSaleUseCase]'s single-account shape rather than
 * replaying every `PurchaseOrderLine`'s own account as a separate debit
 * line. If POP later needs split account routing (e.g. a PO with GOODS
 * and SERVICE lines hitting different accounts), that's a multi-line
 * extension to design then, not guessed here.
 *
 * [CreditorId] reused from `domain.purchasing` as a plain, opaque tag
 * value - the same non-authoritative-identifier treatment [CustomerId]
 * gets on [RecordSaleUseCase] even though `Creditor` itself is expected
 * to eventually live entirely in POP (design doc Section 0).
 */
class RecordVendorObligationUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val expenseOrAssetAccountId: AccountId,
        val apControlAccountId: AccountId,
        val amount: Money,
        val vendorId: CreditorId,
        val description: String? = null
    )

    fun execute(request: Request): RecordVendorObligationResult {
        if (request.amount.amount.signum() <= 0) {
            return RecordVendorObligationResult.InvalidAmount
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordVendorObligationResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordVendorObligationResult.PeriodNotOpen
        }

        val expenseOrAssetAccount = accountRepository.findById(request.expenseOrAssetAccountId)
            ?: return RecordVendorObligationResult.ExpenseOrAssetAccountNotFound(request.expenseOrAssetAccountId)
        val apControlAccount = accountRepository.findById(request.apControlAccountId)
            ?: return RecordVendorObligationResult.ApControlAccountNotFound(request.apControlAccountId)

        val lines = listOf(
            JournalLine(expenseOrAssetAccount.id, request.amount, TransactionSide.DEBIT),
            JournalLine(
                apControlAccount.id, request.amount, TransactionSide.CREDIT,
                mapOf(DimensionType.VENDOR to request.vendorId.value.toString())
            )
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.INTEGRATION, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordVendorObligationUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(expenseOrAssetAccount, apControlAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordVendorObligationResult.Success(entry, entry.pullDomainEvents())
    }
}
