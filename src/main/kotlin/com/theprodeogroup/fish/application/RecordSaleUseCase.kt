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
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outcome of [RecordSaleUseCase.execute] - a sealed `Result`, same
 * reasoning as every other multi-reason posting use case this codebase
 * has built.
 */
sealed class RecordSaleResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordSaleResult()
    data object InvalidAmount : RecordSaleResult()
    data object PeriodNotFound : RecordSaleResult()
    data object PeriodNotOpen : RecordSaleResult()
    data class ArControlAccountNotFound(val accountId: AccountId) : RecordSaleResult()
    data class RevenueAccountNotFound(val accountId: AccountId) : RecordSaleResult()
    data class VatControlAccountNotFound(val accountId: AccountId) : RecordSaleResult()
    data class VatCategoryNotSupported(val category: VatCategory) : RecordSaleResult()
}

/**
 * The *Record sale* thin posting interface
 * (docs/Sales_Order_Processing_DDD_Design.md Section 0/4) - Dr AR
 * Control, Cr Revenue, plus (2026-09-19, docs/IE/IE_VAT_MVP_Design.md) Cr
 * VAT Control per category present. Called by `fish-sales-order-processing`
 * (SOP) once it resolves its own contractually-correct revenue
 * recognition point.
 *
 * **VAT rate resolution and computation happen here, atomically, in the
 * same call that posts the entry** - a direct instruction ("the required
 * atomicity is high") deliberately rejecting the alternative of SOP
 * fetching a rate via a separate GET first and passing a pre-computed
 * amount, which has a staleness/race window between the two calls. SOP
 * sends each line's net amount + [VatCategory] + the transaction date;
 * this use case resolves the rate `asOf` that date via [vatRateSchedule]
 * and computes the VAT amount itself.
 *
 * **One VAT line per distinct category present, not one netted line** -
 * necessary for [DimensionType.VAT_CATEGORY] tagging to mean anything
 * (one dimension value per `JournalLine`), and what makes `VatReturn`'s
 * per-category audit breakdown possible. [VatCategory.ZERO_RATED] and
 * [VatCategory.EXEMPT] both compute to a zero VAT amount and are omitted
 * from the posted lines entirely - "nothing to post" for a zero amount,
 * the same precedent `PayRun.post()`/every other zero-side-omission case
 * in this codebase already established - **not** the same thing as
 * "no VAT line ever needed for this category," which would be
 * indistinguishable from an ordinary untagged/未taxed line at read time
 * were it posted as an explicit zero.
 *
 * Deliberately posts no COGS/Inventory lines, unchanged from before this
 * VAT reshape - see the original KDoc history in git for that reasoning
 * (Inventory Management's own posting fires independently).
 *
 * [CustomerId] reused from `domain.sales` as a plain, opaque tag value,
 * unchanged from before this reshape.
 *
 * **`vatRateSchedule` lives on [Request], not the constructor (2026-09-21)** -
 * originally a constructor default (`= VatRateSchedule.IRELAND`), which
 * meant every Company posting through this use case got Irish VAT rates
 * regardless of its actual jurisdiction, since this class is wired once
 * at app startup, not per-request. Fixed to mirror [ComputeTaxUseCase.Request.taxRule]'s
 * own shape exactly: the caller (the route) resolves the correct schedule
 * via `VatRateSchedule.forJurisdiction(company.jurisdiction)` and supplies
 * it per call - no default here, so nothing can silently fall back to
 * the wrong jurisdiction's rates the way the old constructor default did.
 */
class RecordSaleUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    /** One line of the sale - net amount plus the VAT category it falls under (docs/IE/IE_VAT_MVP_Design.md Decision 1: category lives on the line). */
    data class SaleLine(val netAmount: Money, val vatCategory: VatCategory) {
        init {
            require(netAmount.amount.signum() > 0) { "SaleLine netAmount must be positive" }
        }
    }

    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val arControlAccountId: AccountId,
        val revenueAccountId: AccountId,
        val vatControlAccountId: AccountId,
        val lines: List<SaleLine>,
        val customerId: CustomerId,
        val vatRateSchedule: VatRateSchedule,
        val description: String? = null
    )

    fun execute(request: Request): RecordSaleResult {
        if (request.lines.isEmpty()) {
            return RecordSaleResult.InvalidAmount
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordSaleResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordSaleResult.PeriodNotOpen
        }

        val arAccount = accountRepository.findById(request.arControlAccountId)
            ?: return RecordSaleResult.ArControlAccountNotFound(request.arControlAccountId)
        val revenueAccount = accountRepository.findById(request.revenueAccountId)
            ?: return RecordSaleResult.RevenueAccountNotFound(request.revenueAccountId)
        val vatAccount = accountRepository.findById(request.vatControlAccountId)
            ?: return RecordSaleResult.VatControlAccountNotFound(request.vatControlAccountId)

        val unsupportedCategory = request.lines.map { it.vatCategory }.firstOrNull { !request.vatRateSchedule.supports(it) }
        if (unsupportedCategory != null) {
            return RecordSaleResult.VatCategoryNotSupported(unsupportedCategory)
        }

        val currency = request.lines.first().netAmount.currency
        val zero = Money(BigDecimal.ZERO, currency)
        val netTotal = request.lines.fold(zero) { sum, line -> sum + line.netAmount }

        val vatByCategory = request.lines
            .groupBy { it.vatCategory }
            .mapValues { (category, lines) ->
                lines.fold(zero) { sum, line -> sum + request.vatRateSchedule.vatAmountFor(category, line.netAmount, request.date) }
            }
            .filterValues { it.amount.signum() > 0 }

        val vatTotal = vatByCategory.values.fold(zero) { sum, amount -> sum + amount }
        val grossTotal = netTotal + vatTotal

        val vatLines = vatByCategory.map { (category, amount) ->
            JournalLine(vatAccount.id, amount, TransactionSide.CREDIT, mapOf(DimensionType.VAT_CATEGORY to category.name))
        }

        val lines = listOf(
            JournalLine(
                arAccount.id, grossTotal, TransactionSide.DEBIT,
                mapOf(DimensionType.CUSTOMER to request.customerId.value.toString())
            ),
            JournalLine(revenueAccount.id, netTotal, TransactionSide.CREDIT)
        ) + vatLines

        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.INTEGRATION, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordSaleUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOfNotNull(arAccount, revenueAccount, vatAccount.takeIf { vatLines.isNotEmpty() })
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordSaleResult.Success(entry, entry.pullDomainEvents())
    }
}
