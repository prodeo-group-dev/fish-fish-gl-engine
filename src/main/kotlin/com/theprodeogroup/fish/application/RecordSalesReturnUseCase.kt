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
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.sales.CustomerId
import java.time.LocalDate

/**
 * Outcome of [RecordSalesReturnUseCase.execute] - a sealed `Result`,
 * same reasoning as every other multi-reason posting use case this
 * codebase has built.
 */
sealed class RecordSalesReturnResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordSalesReturnResult()
    data object InvalidAmount : RecordSalesReturnResult()
    data object PeriodNotFound : RecordSalesReturnResult()
    data object PeriodNotOpen : RecordSalesReturnResult()
    data class SalesReturnsAccountNotFound(val accountId: AccountId) : RecordSalesReturnResult()
    data class ArControlAccountNotFound(val accountId: AccountId) : RecordSalesReturnResult()
}

/**
 * The *Record sales return* thin posting interface
 * (docs/Returns_Inwards_Requirements_Specification.md Section 3.5,
 * FR-IF2's credit-note line: "Dr Sales Returns (contra revenue) / Cr
 * Accounts Receivable") - the mirror image of [RecordSaleUseCase]'s Dr
 * AR/Cr Revenue. Called by `fish-sales-order-processing` (SOP) once its
 * own `CreditNote` is issued against an approved-and-inspected
 * `ReturnRequest` - not driven by any aggregate in this repo, the same
 * "thin, no-owning-aggregate" shape [RecordSaleUseCase]/
 * [RecordCollectionUseCase] already established.
 *
 * Deliberately posts only the credit-note/AR side of a return, not the
 * inventory-value side (FR-IF2's other line: "Dr Inventory / Cr Sales
 * Returns or Cost of Sales adjustment" for a resaleable disposition) -
 * that posting depends on IM's own goods-receipt-against-return
 * crossing, which is a separate, not-yet-built increment
 * (docs/Returns_Inwards_Requirements_Specification.md Section 7).
 */
class RecordSalesReturnUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val salesReturnsAccountId: AccountId,
        val arControlAccountId: AccountId,
        val amount: Money,
        val customerId: CustomerId,
        val description: String? = null
    )

    fun execute(request: Request): RecordSalesReturnResult {
        if (request.amount.amount.signum() <= 0) {
            return RecordSalesReturnResult.InvalidAmount
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordSalesReturnResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordSalesReturnResult.PeriodNotOpen
        }

        val salesReturnsAccount = accountRepository.findById(request.salesReturnsAccountId)
            ?: return RecordSalesReturnResult.SalesReturnsAccountNotFound(request.salesReturnsAccountId)
        val arAccount = accountRepository.findById(request.arControlAccountId)
            ?: return RecordSalesReturnResult.ArControlAccountNotFound(request.arControlAccountId)

        val lines = listOf(
            JournalLine(salesReturnsAccount.id, request.amount, TransactionSide.DEBIT),
            JournalLine(
                arAccount.id, request.amount, TransactionSide.CREDIT,
                mapOf(DimensionType.CUSTOMER to request.customerId.value.toString())
            )
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.INTEGRATION, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordSalesReturnUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(salesReturnsAccount, arAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordSalesReturnResult.Success(entry, entry.pullDomainEvents())
    }
}
