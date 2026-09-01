package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
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
 * **No stock check here (2026-09-01, "Retire GL's StockItem from its
 * legacy costing" - "GL only needs the monetary value of the assets
 * and whether the inventory is for resale/trading... or for expenses").**
 * This use case used to check/decrement GL's own legacy `StockItem`
 * for a [SaleType.GOODS] sale - that entire mechanism (`StockItemRepository`,
 * `StockShortageEscalation`, `callerCanOverrideStockCheck`) is gone.
 * IM is the source of truth for inventory now; a caller who needs a
 * stock check performs it against IM *before* calling this route (the
 * same "check against IM first, then post the financial effect"
 * sequencing `RecordOrdinarySaleUseCase` already established for SOP's
 * own CREDIT+GOODS path). This use case's own job narrows to exactly
 * what GL needs: the monetary effect, nothing about which physical
 * item or how many units moved.
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
        val description: String? = null
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
