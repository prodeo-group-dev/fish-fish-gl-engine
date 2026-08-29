package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.inventory.StockShortageEscalation
import com.theprodeogroup.fish.domain.inventory.StockShortageEscalationRepository
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.sales.Customer
import com.theprodeogroup.fish.domain.sales.CustomerRepository
import com.theprodeogroup.fish.domain.sales.SaleMethod
import com.theprodeogroup.fish.domain.sales.SaleType
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecord
import com.theprodeogroup.fish.domain.sales.SalesInvoiceRecordRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outcome of [CreateSalesInvoiceUseCase.execute] - a sealed `Result`,
 * same reasoning as [RecordSaleResult]. [Success.paid] is `true` for a
 * [SaleMethod.CASH] sale (invoice and receipt in one flow) and `false`
 * for [SaleMethod.CREDIT] (an outstanding receivable), so a caller can
 * render the generated document as a receipt or an invoice without
 * re-deriving it from [Success.journalEntry]'s lines.
 */
sealed class CreateSalesInvoiceResult {
    data class Success(
        val journalEntry: JournalEntry,
        val customer: Customer,
        val invoiceNumber: String,
        val paid: Boolean,
        val events: List<DomainEvent>
    ) : CreateSalesInvoiceResult()
    data object InvalidAmount : CreateSalesInvoiceResult()
    data object BlankCustomerName : CreateSalesInvoiceResult()
    data object NoOpenPeriod : CreateSalesInvoiceResult()
    data object ArControlAccountNotConfigured : CreateSalesInvoiceResult()
    data object CashAccountNotConfigured : CreateSalesInvoiceResult()
    data object RevenueAccountNotConfigured : CreateSalesInvoiceResult()
    data object MissingStockItemSelection : CreateSalesInvoiceResult()
    data class StockItemNotFound(val stockItemId: StockItemId) : CreateSalesInvoiceResult()
    data class InsufficientStock(
        val stockItemId: StockItemId,
        val requestedQuantity: BigDecimal,
        val quantityOnHand: BigDecimal
    ) : CreateSalesInvoiceResult()
}

/**
 * The business-owner-facing "record a sale" entry point behind the SOP
 * dashboard tab - unlike [RecordSaleUseCase] (a thin posting interface
 * for a machine caller that already knows periodId/account IDs), this
 * resolves the company's open Period and its Cash/Accounts Receivable
 * control/Revenue accounts itself from the Chart of Accounts template
 * (`ChartOfAccountsTemplate`: Cash is code "1000", AR is "1100",
 * Revenue is the lowest-coded REVENUE-type account, "4000" under every
 * template that has one), and finds-or-creates the named [Customer]
 * rather than requiring a caller-supplied `CustomerId` - a business
 * owner types a customer name on a form, not a UUID.
 *
 * Two independent axes, per the user's explicit instructions:
 * [SaleType] (goods/service, cosmetic - carried onto the eInvoice, both
 * post identically) and [SaleMethod] (cash/credit, which genuinely
 * changes the posting - see its own KDoc). A credit sale requires a
 * real customer name (there's no such thing as an anonymous debtor); a
 * cash sale's name is optional and falls back to a shared, per-Company
 * "Unregistered Cash Customer" [Customer], found-or-created the same
 * way a named one is - the pooled record's own growing history is what
 * the "register for the loyalty programme" nudge on the eReceipt is
 * selling.
 *
 * [Customer.recordSale] only runs for [SaleMethod.CREDIT] - a cash sale
 * never creates a receivable, so raising the subsidiary-ledger balance
 * for one would be wrong. When it does run, it's the same pairing
 * [Customer.receivePayment] already established for the collection
 * side - without it, AR aging/ECL would silently miss every credit
 * sale made through this entry point.
 *
 * **Stock check for a [SaleType.GOODS] sale (2026-08-29, closing the gap
 * flagged right after this use case first shipped without one).** A
 * GOODS sale requires [Request.stockItemId]/[Request.quantity] -
 * [StockItemRepository.findById] resolves the [com.theprodeogroup.fish.domain.inventory.StockItem],
 * and its `recordIssue` guard (checks-then-mutates, never partially
 * applied) is the actual stock check: if [com.theprodeogroup.fish.domain.inventory.StockItem.quantityOnHand]
 * covers the requested quantity, it's decremented and saved right here,
 * so a second identical sale correctly sees the reduced balance instead
 * of the check going stale. If it doesn't, per the user's own
 * instructions: a [StockShortageEscalation] is always persisted first
 * ("the request for the item is logged and escalated to the owner" -
 * a real, queryable record, not a log line) via [StockShortageEscalationRepository];
 * then, if [Request.callerCanOverrideStockCheck] is `false` (an
 * ordinary WRITE-level caller), the sale is hard-rejected
 * ([CreateSalesInvoiceResult.InsufficientStock], nothing posts). If
 * `true` (an APPROVE-level caller - "it must have an approver status
 * in the least to pass"), the sale proceeds and posts revenue anyway,
 * with the shortfall left visible rather than forcing
 * `quantityOnHand` negative, a fabricated concept nobody asked for -
 * physically restocking, and recording the eventual goods issue once
 * stock exists, stays the Owner's own follow-up via the already-built
 * `RecordInventoryIssueUseCase`. [Request.callerCanOverrideStockCheck]
 * and [Request.requestedByEmail] are both resolved by the route layer
 * from the authenticated caller's `Membership`/`User` - this use case
 * takes plain values, no auth types, matching every other use case in
 * this package. A [SaleType.SERVICE] sale skips all of this - there's
 * no physical stock to check. `POP` linkage (tracing the goods back to
 * the purchase that brought them in) is still unbuilt - only the stock-
 * level side of the original gap is closed here.
 *
 * **Sales listing (2026-08-29, user request: "a listing of sales (each
 * timestamped) on the SOP screen").** Every success also saves a
 * [SalesInvoiceRecord] - a thin, append-only log alongside the posted
 * [JournalEntry], not a new source of financial truth. See its own
 * KDoc for why a real `Instant` (not the entry's business `date`) is
 * needed for "timestamped" to mean anything when two sales share a day.
 *
 * The returned `invoiceNumber` is derived from the JournalEntry's own
 * generated id (`INV-` + its first 8 hex characters, uppercased) rather
 * than a separate persisted sequence - simple and unique, not a legally
 * sequential numbering scheme. Matches the user's explicit scope choice
 * of "a real, styled invoice document," not a formal e-invoicing
 * standard (UBL/PEPPOL/a tax-authority format).
 */
class CreateSalesInvoiceUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val customerRepository: CustomerRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val stockItemRepository: StockItemRepository,
    private val stockShortageEscalationRepository: StockShortageEscalationRepository,
    private val salesInvoiceRecordRepository: SalesInvoiceRecordRepository
) {
    data class Request(
        val companyId: CompanyId,
        val saleType: SaleType,
        val saleMethod: SaleMethod,
        val customerName: String?,
        val amount: Money,
        val date: LocalDate,
        val requestedByEmail: String,
        val description: String? = null,
        val stockItemId: StockItemId? = null,
        val quantity: BigDecimal? = null,
        val callerCanOverrideStockCheck: Boolean = false
    )

    fun execute(request: Request): CreateSalesInvoiceResult {
        if (request.amount.amount.signum() <= 0) {
            return CreateSalesInvoiceResult.InvalidAmount
        }

        val rawName = request.customerName?.trim().orEmpty()
        val customerName = when {
            rawName.isNotBlank() -> rawName
            request.saleMethod == SaleMethod.CASH -> UNREGISTERED_CASH_CUSTOMER_NAME
            else -> return CreateSalesInvoiceResult.BlankCustomerName
        }

        val period = periodRepository.findAllByCompany(request.companyId).firstOrNull { it.allowsPosting() }
            ?: return CreateSalesInvoiceResult.NoOpenPeriod

        val accounts = accountRepository.findAllByCompany(request.companyId)
        val revenueAccount = accounts.filter { it.type == AccountType.REVENUE }.minByOrNull { it.code }
            ?: return CreateSalesInvoiceResult.RevenueAccountNotConfigured
        val debitAccount = when (request.saleMethod) {
            SaleMethod.CREDIT -> accounts.firstOrNull { it.type == AccountType.ASSET && it.code == "1100" }
                ?: return CreateSalesInvoiceResult.ArControlAccountNotConfigured
            SaleMethod.CASH -> accounts.firstOrNull { it.type == AccountType.ASSET && it.code == "1000" }
                ?: return CreateSalesInvoiceResult.CashAccountNotConfigured
        }

        if (request.saleType == SaleType.GOODS) {
            val stockItemId = request.stockItemId
            val quantity = request.quantity
            if (stockItemId == null || quantity == null || quantity.signum() <= 0) {
                return CreateSalesInvoiceResult.MissingStockItemSelection
            }
            val stockItem = stockItemRepository.findById(stockItemId)
                ?.takeIf { it.companyId == request.companyId }
                ?: return CreateSalesInvoiceResult.StockItemNotFound(stockItemId)

            val quantityOnHandBeforeIssue = stockItem.quantityOnHand
            val issueResult = stockItem.recordIssue(quantity)
            if (issueResult.isValid) {
                stockItemRepository.save(stockItem)
            } else {
                stockShortageEscalationRepository.save(
                    StockShortageEscalation.create(
                        request.companyId, stockItemId, quantity, quantityOnHandBeforeIssue, request.requestedByEmail,
                        overridden = request.callerCanOverrideStockCheck
                    )
                )
                if (!request.callerCanOverrideStockCheck) {
                    return CreateSalesInvoiceResult.InsufficientStock(stockItemId, quantity, quantityOnHandBeforeIssue)
                }
            }
        }

        val customer = customerRepository.findAllByCompany(request.companyId)
            .firstOrNull { it.name.equals(customerName, ignoreCase = true) }
            ?: Customer.create(request.companyId, customerName, request.amount.currency)

        if (request.saleMethod == SaleMethod.CREDIT) {
            val recordResult = customer.recordSale(request.amount)
            if (!recordResult.isValid) {
                return CreateSalesInvoiceResult.InvalidAmount
            }
        }

        val itemDescription = buildString {
            append(if (request.saleType == SaleType.GOODS) "Sale of goods" else "Sale of service")
            append(if (request.saleMethod == SaleMethod.CASH) " (cash) - " else " (credit) - ")
            append(customerName)
            if (!request.description.isNullOrBlank()) {
                append(" (")
                append(request.description.trim())
                append(")")
            }
        }

        val lines = when (request.saleMethod) {
            SaleMethod.CREDIT -> listOf(
                JournalLine(
                    debitAccount.id, request.amount, TransactionSide.DEBIT,
                    mapOf(DimensionType.CUSTOMER to customer.id.value.toString())
                ),
                JournalLine(revenueAccount.id, request.amount, TransactionSide.CREDIT)
            )
            SaleMethod.CASH -> listOf(
                JournalLine(
                    debitAccount.id, request.amount, TransactionSide.DEBIT,
                    mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.OPERATING.name)
                ),
                JournalLine(
                    revenueAccount.id, request.amount, TransactionSide.CREDIT,
                    mapOf(DimensionType.CUSTOMER to customer.id.value.toString())
                )
            )
        }
        val entry = JournalEntry.create(period.id, request.date, lines, JournalSource.MANUAL, itemDescription)
        val posting = entry.post()
        check(posting.isValid) {
            "CreateSalesInvoiceUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(debitAccount, revenueAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        customerRepository.save(customer)
        journalEntryRepository.save(entry)

        val invoiceNumber = "INV-" + entry.id.value.toString().replace("-", "").take(8).uppercase()
        val paid = request.saleMethod == SaleMethod.CASH

        salesInvoiceRecordRepository.save(
            SalesInvoiceRecord.create(
                request.companyId, entry.id, invoiceNumber, customer.id, customer.name,
                request.saleType, request.saleMethod, request.amount, paid, request.description
            )
        )

        return CreateSalesInvoiceResult.Success(entry, customer, invoiceNumber, paid, entry.pullDomainEvents())
    }

    companion object {
        const val UNREGISTERED_CASH_CUSTOMER_NAME = "Unregistered Cash Customer"
    }
}
