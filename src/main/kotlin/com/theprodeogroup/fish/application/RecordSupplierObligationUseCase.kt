package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
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
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatLineDirection
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outcome of [RecordSupplierObligationUseCase.execute] - a sealed
 * `Result`, same reasoning as every other multi-reason posting use case
 * this codebase has built, mirroring [RecordSaleResult] exactly.
 */
sealed class RecordSupplierObligationResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>,
        /** The whole credit to the payable: every line's net plus the VAT on them (what the supplier is owed), whichever account a line debited. */
        val grossTotal: Money,
        /** The input VAT inside [grossTotal]; zero when every line is zero-rated or exempt. */
        val vatTotal: Money
    ) : RecordSupplierObligationResult()
    data object InvalidAmount : RecordSupplierObligationResult()
    data object PeriodNotFound : RecordSupplierObligationResult()
    data object PeriodNotOpen : RecordSupplierObligationResult()
    data class ExpenseOrAssetAccountNotFound(val accountId: AccountId) : RecordSupplierObligationResult()
    data class ApControlAccountNotFound(val accountId: AccountId) : RecordSupplierObligationResult()
    data class VatControlAccountNotFound(val accountId: AccountId) : RecordSupplierObligationResult()
    /** A line named an account that is not an account of this Company. */
    data class LineAccountNotFound(val accountId: AccountId) : RecordSupplierObligationResult()
    /** A line named an account of this Company that a purchase line may not post to (wrong type, a control or module-owned account, or inactive). */
    data class LineAccountNotAllowed(val accountId: AccountId, val reason: LineAccountResolver.Reason) : RecordSupplierObligationResult()
    /** No account was named anywhere and the Company has no Suspense account to fall back to. */
    data object SuspenseAccountNotConfigured : RecordSupplierObligationResult()
    data class VatCategoryNotSupported(val category: VatCategory) : RecordSupplierObligationResult()
    /** The category is configured, but no rate of it is in force on the transaction date: refused, never defaulted. */
    data class VatRateNotInForce(val category: VatCategory, val asOf: LocalDate) : RecordSupplierObligationResult()
}

/**
 * The *Record supplier obligation* thin posting interface
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
 *
 * **`vatRateSchedule` lives on [Request], not the constructor (2026-09-21)** -
 * mirrors [RecordSaleUseCase]'s identical fix, for the identical reason
 * (see its own KDoc): a constructor default meant every Company got
 * Irish VAT rates regardless of jurisdiction, since this class is wired
 * once at app startup. The route now resolves the correct schedule via
 * `VatRateSchedule.forJurisdiction(company.jurisdiction)` and supplies
 * it per call.
 */
class RecordSupplierObligationUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    companyRepository: CompanyRepository? = null
) {
    private val lineAccountResolver = LineAccountResolver(accountRepository, companyRepository)

    /**
     * One line of the purchase - net amount plus the VAT category it falls under, and (2026-10-18, Femi: the account
     * a transaction posts to is chosen by its type) the account from the Company's own chart it posts to: rent to Rent,
     * electricity to Electricity. [accountId] is optional: a line that names none uses [Request.expenseOrAssetAccountId]
     * when the caller still sends one, otherwise the Company's Suspense account.
     */
    data class PurchaseLine(val netAmount: Money, val vatCategory: VatCategory, val accountId: AccountId? = null) {
        init {
            require(netAmount.amount.signum() > 0) { "PurchaseLine netAmount must be positive" }
        }
    }

    data class Request(
        /** The Company the caller was authorized against - the Period must belong to it (T19 / F9). */
        val companyId: CompanyId,
        val periodId: PeriodId,
        val date: LocalDate,
        /** The account for every line that names none (legacy callers, which send one account for the whole purchase); `null` means those lines go to Suspense. Checked only for belonging to the Company, as it always was. */
        val expenseOrAssetAccountId: AccountId?,
        val apControlAccountId: AccountId,
        val vatControlAccountId: AccountId,
        val lines: List<PurchaseLine>,
        val supplierId: SupplierId,
        val vatRateSchedule: VatRateSchedule,
        val description: String? = null,
        /** Caller-supplied, defaulting to [JournalSource.INTEGRATION] (2026-10-01) - same generalization already applied to [RecordOpeningBalanceUseCase.Request.journalSource]/[CreateFixedAssetUseCase.Request.journalSource]/[RecordInventoryReceiptUseCase.Request.journalSource], needed so docs/Opening_Figures_CSV_Upload_DDD_Design.md's AP importer (POP) can tag its postings [JournalSource.IMPORT]. Added last, not interleaved, so every existing positional-argument call site keeps compiling unchanged. */
        val journalSource: JournalSource = JournalSource.INTEGRATION
    )

    fun execute(request: Request): RecordSupplierObligationResult {
        if (request.lines.isEmpty()) {
            return RecordSupplierObligationResult.InvalidAmount
        }

        val period = periodRepository.findOwnedBy(request.periodId, request.companyId)
            ?: return RecordSupplierObligationResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordSupplierObligationResult.PeriodNotOpen
        }

        // Cross-tenant isolation fix (2026-09-23, same gap/fix as PostJournalEntryUseCase):
        // each Account must belong to this Period's own Company, or it's treated as
        // AccountNotFound - never a distinct "forbidden" case, so this never confirms
        // another Company's Account exists.
        val requestLevelAccount = request.expenseOrAssetAccountId?.let { accountId ->
            accountRepository.findById(accountId)
                ?.takeIf { it.companyId == period.companyId }
                ?: return RecordSupplierObligationResult.ExpenseOrAssetAccountNotFound(accountId)
        }
        val apControlAccount = accountRepository.findById(request.apControlAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordSupplierObligationResult.ApControlAccountNotFound(request.apControlAccountId)
        val vatAccount = accountRepository.findById(request.vatControlAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordSupplierObligationResult.VatControlAccountNotFound(request.vatControlAccountId)

        val unsupportedCategory = request.lines.map { it.vatCategory }.firstOrNull { !request.vatRateSchedule.supports(it) }
        if (unsupportedCategory != null) {
            return RecordSupplierObligationResult.VatCategoryNotSupported(unsupportedCategory)
        }
        val ratesInForce = request.vatRateSchedule.categoriesAsOf(request.date).map { it.category }.toSet()
        request.lines.map { it.vatCategory }.firstOrNull { it !in ratesInForce }?.let {
            return RecordSupplierObligationResult.VatRateNotInForce(it, request.date)
        }

        // Each line's account: its own, else the request-level one, else Suspense - all resolved before anything is built.
        val lineAccounts = mutableListOf<Account>()
        for (line in request.lines) {
            val resolved = when {
                line.accountId != null -> lineAccountResolver.resolve(period.companyId, LineAccountResolver.Kind.PURCHASE, line.accountId)
                requestLevelAccount != null -> LineAccountResolver.Result.Resolved(requestLevelAccount)
                else -> lineAccountResolver.suspense(period.companyId)
            }
            when (resolved) {
                is LineAccountResolver.Result.Resolved -> lineAccounts.add(resolved.account)
                is LineAccountResolver.Result.NotFound -> return RecordSupplierObligationResult.LineAccountNotFound(resolved.accountId)
                is LineAccountResolver.Result.NotAllowed ->
                    return RecordSupplierObligationResult.LineAccountNotAllowed(resolved.accountId, resolved.reason)
                LineAccountResolver.Result.SuspenseNotConfigured -> return RecordSupplierObligationResult.SuspenseAccountNotConfigured
            }
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

        // Input VAT: the buyer's side of the VAT return, tagged so the return does not depend on the debit side.
        val vatLines = vatByCategory.map { (category, amount) ->
            JournalLine(
                vatAccount.id, amount, TransactionSide.DEBIT,
                mapOf(DimensionType.VAT_CATEGORY to category.name, DimensionType.VAT_DIRECTION to VatLineDirection.INPUT.name)
            )
        }

        // One debit per distinct account (net amounts summed), in the order the accounts first appear on the lines.
        val debitAccounts = LinkedHashMap<AccountId, Account>()
        val netByAccount = LinkedHashMap<AccountId, Money>()
        request.lines.zip(lineAccounts).forEach { (line, account) ->
            debitAccounts[account.id] = account
            netByAccount[account.id] = (netByAccount[account.id] ?: zero) + line.netAmount
        }
        val netLines = netByAccount.map { (accountId, net) -> JournalLine(accountId, net, TransactionSide.DEBIT) }

        val lines = netLines + vatLines + listOf(
            JournalLine(
                apControlAccount.id, grossTotal, TransactionSide.CREDIT,
                mapOf(DimensionType.VENDOR to request.supplierId.value.toString())
            )
        )

        val entry = JournalEntry.create(
            request.periodId, request.date, lines, request.journalSource, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordSupplierObligationUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = debitAccounts.values + listOfNotNull(apControlAccount, vatAccount.takeIf { vatLines.isNotEmpty() })
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordSupplierObligationResult.Success(entry, entry.pullDomainEvents(), grossTotal, vatTotal)
    }
}
