package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatLineDirection
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate

/** Outcome of [RecordSupplierCreditNoteUseCase.execute]: a sealed `Result`, same reasoning as [RecordSupplierObligationResult]. */
sealed class RecordSupplierCreditNoteResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>,
        /** What the payable fell by: the net amounts plus the VAT on them. */
        val creditNoteTotal: Money,
        /** The VAT on the credit note (zero when no line carries VAT). */
        val vat: Money
    ) : RecordSupplierCreditNoteResult()
    data object InvalidAmount : RecordSupplierCreditNoteResult()
    data object PeriodNotFound : RecordSupplierCreditNoteResult()
    data object PeriodNotOpen : RecordSupplierCreditNoteResult()
    data class ApControlAccountNotFound(val accountId: AccountId) : RecordSupplierCreditNoteResult()
    data class DiscountsReceivedAccountNotFound(val accountId: AccountId) : RecordSupplierCreditNoteResult()
    data class DiscountsReceivedAccountNotAllowed(val accountId: AccountId) : RecordSupplierCreditNoteResult()
    data class VatControlAccountNotFound(val accountId: AccountId) : RecordSupplierCreditNoteResult()
    data class VatCategoryNotSupported(val category: VatCategory) : RecordSupplierCreditNoteResult()
    /** The supply date is before the first VAT rate in force for [category] in this jurisdiction. */
    data class VatRateNotInForce(val category: VatCategory, val asOf: LocalDate) : RecordSupplierCreditNoteResult()
}

/**
 * The *Record supplier credit note* thin posting interface (2026-10-16, POP's Discounts Received design, task T-D5;
 * the supplier half of S8 in `docs/Sales_Returns_Credit_Notes_GL_Half.md`): a credit note from a supplier for a price
 * allowance or a rebate ([Kind]). The buyer owes the supplier less and the difference is Discounts Received.
 *
 * Dr Accounts Payable (net + VAT, tagged with the supplier) / Cr Discounts Received (the net, one line) / Cr VAT
 * control (the VAT, one line per category, tagged `VAT_CATEGORY` and `VAT_DIRECTION=INPUT`, because the buyer's
 * reclaimable input VAT falls). GL computes the VAT per category on the Company's verified rate schedule at the rate
 * in force on [Request.originalSupplyDate] (the credit note date by default), the same arithmetic as
 * [RecordSupplierObligationUseCase]; a caller never supplies a VAT figure. There is deliberately no check against the
 * supplier's open balance: a credit note may exceed it (a credit balance), as a sales return may (FR-SR63).
 *
 * Both kinds post identically (one account, 4920, decided 2026-10-15); [Kind] is validated and written into the entry
 * description so a person can tell them apart, and a dimension can separate them later without a new account.
 * The jurisdiction's VAT treatment of a credit note (input VAT falls by the tax shown on it) rests on IE VATCA 2010
 * s.67(1)(b) and UK VAT Regulations 1995 reg 38; it is not the settlement (early-payment) discount rule, which stays
 * out of scope until its SPUTO is approved (`docs/GL_Settlement_Discount_VAT_SPUTO.md`).
 */
class RecordSupplierCreditNoteUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    enum class Kind { DISCOUNT, REBATE }

    /** One line of the credit note: the net amount credited and the VAT category of the supply it credits. */
    data class CreditNoteLine(val netAmount: Money, val vatCategory: VatCategory)

    data class Request(
        /** The Company the caller was authorized against: the Period and every account must belong to it. */
        val companyId: CompanyId,
        val periodId: PeriodId,
        /** The credit note date. */
        val date: LocalDate,
        val apControlAccountId: AccountId,
        val discountsReceivedAccountId: AccountId,
        val vatControlAccountId: AccountId,
        val lines: List<CreditNoteLine>,
        val supplierId: SupplierId,
        val kind: Kind,
        val vatRateSchedule: VatRateSchedule,
        /** The date of the supply being credited; drives the VAT rate. Default: [date]. */
        val originalSupplyDate: LocalDate? = null,
        val description: String? = null
    )

    fun execute(request: Request): RecordSupplierCreditNoteResult {
        if (request.lines.isEmpty() || request.lines.any { it.netAmount.amount.signum() <= 0 }) {
            return RecordSupplierCreditNoteResult.InvalidAmount
        }

        val period = periodRepository.findOwnedBy(request.periodId, request.companyId)
            ?: return RecordSupplierCreditNoteResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordSupplierCreditNoteResult.PeriodNotOpen
        }

        // Each account must belong to this Period's own Company, or it is treated as not found: never a distinct
        // "forbidden" case, so this never confirms another Company's account exists.
        val apControlAccount = accountRepository.findById(request.apControlAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordSupplierCreditNoteResult.ApControlAccountNotFound(request.apControlAccountId)
        val discountsAccount = accountRepository.findById(request.discountsReceivedAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordSupplierCreditNoteResult.DiscountsReceivedAccountNotFound(request.discountsReceivedAccountId)
        if (discountsAccount.type != AccountType.REVENUE) {
            return RecordSupplierCreditNoteResult.DiscountsReceivedAccountNotAllowed(discountsAccount.id)
        }
        val vatAccount = accountRepository.findById(request.vatControlAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordSupplierCreditNoteResult.VatControlAccountNotFound(request.vatControlAccountId)

        val unsupported = request.lines.map { it.vatCategory }.firstOrNull { !request.vatRateSchedule.supports(it) }
        if (unsupported != null) {
            return RecordSupplierCreditNoteResult.VatCategoryNotSupported(unsupported)
        }

        val currency = request.lines.first().netAmount.currency
        val zero = Money(BigDecimal.ZERO, currency)
        val supplyDate = request.originalSupplyDate ?: request.date
        val vatByCategory = try {
            request.lines.groupBy { it.vatCategory }
                .mapValues { (category, lines) ->
                    lines.fold(zero) { sum, line -> sum + request.vatRateSchedule.vatAmountFor(category, line.netAmount, supplyDate) }
                }
                .filterValues { it.amount.signum() > 0 }
        } catch (e: IllegalArgumentException) {
            // The schedule has no rate in force for a category on the supply date: an input problem, not a crash.
            val category = request.lines.map { it.vatCategory }.first { it != VatCategory.EXEMPT }
            return RecordSupplierCreditNoteResult.VatRateNotInForce(category, supplyDate)
        }

        val net = request.lines.fold(zero) { sum, line -> sum + line.netAmount }
        val vat = vatByCategory.values.fold(zero) { sum, amount -> sum + amount }
        val total = net + vat

        val lines = listOf(
            JournalLine(
                apControlAccount.id, total, TransactionSide.DEBIT,
                mapOf(DimensionType.VENDOR to request.supplierId.value.toString())
            ),
            JournalLine(discountsAccount.id, net, TransactionSide.CREDIT)
        ) + vatByCategory.map { (category, amount) ->
            JournalLine(
                vatAccount.id, amount, TransactionSide.CREDIT,
                mapOf(DimensionType.VAT_CATEGORY to category.name, DimensionType.VAT_DIRECTION to VatLineDirection.INPUT.name)
            )
        }

        val description = request.description?.takeIf { it.isNotBlank() }?.let { "${request.kind.name}: $it" } ?: request.kind.name
        val entry = JournalEntry.create(request.periodId, request.date, lines, JournalSource.INTEGRATION, description)
        val posting = entry.post()
        check(posting.isValid) {
            "RecordSupplierCreditNoteUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(apControlAccount, discountsAccount) + listOfNotNull(vatAccount.takeIf { vatByCategory.isNotEmpty() })
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }
        journalEntryRepository.save(entry)

        return RecordSupplierCreditNoteResult.Success(entry, entry.pullDomainEvents(), total, vat)
    }
}
