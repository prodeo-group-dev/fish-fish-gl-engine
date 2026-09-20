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
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import java.math.BigDecimal
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
    data class VatControlAccountNotFound(val accountId: AccountId) : RecordVendorObligationResult()
}

/**
 * The *Record vendor obligation* thin posting interface
 * (docs/Purchase_Order_Processing_DDD_Design.md Section 0/4) - the
 * Purchasing mirror of [RecordSaleUseCase], input VAT instead of output
 * VAT. Dr caller-specified Expense/Asset (net), Dr VAT Control per
 * category present, Cr AP Control (gross), tagged `DimensionType.VENDOR`.
 *
 * **Same atomicity contract as [RecordSaleUseCase]** (2026-09-19, "the
 * required atomicity is high") - POP sends each line's net amount +
 * [VatCategory] + the transaction date; this use case resolves the rate
 * `asOf` that date via [vatRateSchedule] and computes the VAT amount
 * itself, in the same call that posts the entry. See [RecordSaleUseCase]'s
 * own KDoc for the full reasoning, not repeated here.
 *
 * **Single Expense/Asset account, not a list** - unchanged from before
 * this VAT reshape (POP's own `PurchaseOrderLine` KDoc: account routing
 * happens once, at three-way match, not per line at PO creation). VAT
 * categorization is still per-line even though the expense/asset routing
 * isn't - the two are independent granularities.
 */
class RecordVendorObligationUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val vatRateSchedule: VatRateSchedule = VatRateSchedule.IRELAND
) {
    /** One line of the purchase - net amount plus the VAT category it falls under. */
    data class PurchaseLine(val netAmount: Money, val vatCategory: VatCategory) {
        init {
            require(netAmount.amount.signum() > 0) { "PurchaseLine netAmount must be positive" }
        }
    }

    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val expenseOrAssetAccountId: AccountId,
        val apControlAccountId: AccountId,
        val vatControlAccountId: AccountId,
        val lines: List<PurchaseLine>,
        val vendorId: CreditorId,
        val description: String? = null
    )

    fun execute(request: Request): RecordVendorObligationResult {
        if (request.lines.isEmpty()) {
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
        val vatAccount = accountRepository.findById(request.vatControlAccountId)
            ?: return RecordVendorObligationResult.VatControlAccountNotFound(request.vatControlAccountId)

        val currency = request.lines.first().netAmount.currency
        val zero = Money(BigDecimal.ZERO, currency)
        val netTotal = request.lines.fold(zero) { sum, line -> sum + line.netAmount }

        val vatByCategory = request.lines
            .groupBy { it.vatCategory }
            .mapValues { (category, lines) ->
                lines.fold(zero) { sum, line -> sum + vatRateSchedule.vatAmountFor(category, line.netAmount, request.date) }
            }
            .filterValues { it.amount.signum() > 0 }

        val vatTotal = vatByCategory.values.fold(zero) { sum, amount -> sum + amount }
        val grossTotal = netTotal + vatTotal

        val vatLines = vatByCategory.map { (category, amount) ->
            JournalLine(vatAccount.id, amount, TransactionSide.DEBIT, mapOf(DimensionType.VAT_CATEGORY to category.name))
        }

        val lines = listOf(
            JournalLine(expenseOrAssetAccount.id, netTotal, TransactionSide.DEBIT)
        ) + vatLines + listOf(
            JournalLine(
                apControlAccount.id, grossTotal, TransactionSide.CREDIT,
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

        val touchedAccounts: List<Account> = listOfNotNull(expenseOrAssetAccount, apControlAccount, vatAccount.takeIf { vatLines.isNotEmpty() })
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordVendorObligationResult.Success(entry, entry.pullDomainEvents())
    }
}
