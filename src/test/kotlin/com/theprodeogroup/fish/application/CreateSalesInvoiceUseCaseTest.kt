package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.sales.SaleMethod
import com.theprodeogroup.fish.domain.sales.SaleType
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 29)
private const val REQUESTED_BY = "cashier@example.com"

/**
 * The business-owner-facing "record a sale" entry point behind the SOP
 * dashboard tab - unlike RecordSaleUseCaseTest's machine caller, this
 * resolves Period/Cash/AR/Revenue itself from the Chart of Accounts
 * template and finds-or-creates the named Customer.
 *
 * No stock check happens here (2026-09-01, "Retire GL's StockItem from
 * its legacy costing" - see the use case's own KDoc) - `SaleType` is
 * cosmetic now, carried onto the eInvoice only.
 */
class CreateSalesInvoiceUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val customerRepository = FakeCustomerRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val salesInvoiceRecordRepository = FakeSalesInvoiceRecordRepository()
    private val useCase = CreateSalesInvoiceUseCase(
        periodRepository, accountRepository, customerRepository, journalEntryRepository, salesInvoiceRecordRepository
    )

    private val companyId = CompanyId.generate()

    private fun openPeriod(): Period {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun account(code: String, type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        val account = Account.create(companyId, type, classification, code, "Test Account")
        accountRepository.save(account)
        return account
    }

    private fun seedCoa() {
        account("1000", AccountType.ASSET) // Cash
        account("1100", AccountType.ASSET) // Accounts Receivable
        account("4000", AccountType.REVENUE)
    }

    private fun request(
        saleMethod: SaleMethod,
        customerName: String? = "Jane Doe",
        amount: String = "150.00",
        saleType: SaleType = SaleType.SERVICE
    ) = CreateSalesInvoiceUseCase.Request(
        companyId, saleType, saleMethod, customerName, Money(BigDecimal(amount), GBP), TODAY, REQUESTED_BY, "Bag of rice"
    )

    @Test
    fun `given a credit sale for a named customer, when executed, then it debits AR and credits Revenue, tagging AR with the Customer, and raises the Customer's receivable balance`() {
        openPeriod()
        seedCoa()

        val result = useCase.execute(request(SaleMethod.CREDIT))

        val success = result.shouldBeInstanceOf<CreateSalesInvoiceResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.paid shouldBe false
        val debitLine = success.journalEntry.lines.first { it.side == TransactionSide.DEBIT }
        val creditLine = success.journalEntry.lines.first { it.side == TransactionSide.CREDIT }
        debitLine.dimensions[DimensionType.CUSTOMER] shouldBe success.customer.id.value.toString()
        creditLine.dimensions[DimensionType.CUSTOMER] shouldBe null
        success.customer.name shouldBe "Jane Doe"
        success.customer.balance shouldBe Money(BigDecimal("150.00"), GBP)
        salesInvoiceRecordRepository.saveCalls.single().invoiceNumber shouldBe success.invoiceNumber
        salesInvoiceRecordRepository.saveCalls.single().paid shouldBe false
    }

    @Test
    fun `given a cash sale for a named customer, when executed, then it debits Cash and credits Revenue tagged with the Customer, and does not raise a receivable balance`() {
        openPeriod()
        seedCoa()

        val result = useCase.execute(request(SaleMethod.CASH))

        val success = result.shouldBeInstanceOf<CreateSalesInvoiceResult.Success>()
        success.paid shouldBe true
        val creditLine = success.journalEntry.lines.first { it.side == TransactionSide.CREDIT }
        creditLine.dimensions[DimensionType.CUSTOMER] shouldBe success.customer.id.value.toString()
        success.customer.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a cash sale with a blank customer name, when executed, then it pools onto the shared Unregistered Cash Customer`() {
        openPeriod()
        seedCoa()

        val result = useCase.execute(request(SaleMethod.CASH, customerName = ""))

        val success = result.shouldBeInstanceOf<CreateSalesInvoiceResult.Success>()
        success.customer.name shouldBe CreateSalesInvoiceUseCase.UNREGISTERED_CASH_CUSTOMER_NAME
    }

    @Test
    fun `given two cash sales with a blank customer name, when executed, then both pool onto the same Customer record`() {
        openPeriod()
        seedCoa()

        val first = useCase.execute(request(SaleMethod.CASH, customerName = null)).shouldBeInstanceOf<CreateSalesInvoiceResult.Success>()
        val second = useCase.execute(request(SaleMethod.CASH, customerName = null)).shouldBeInstanceOf<CreateSalesInvoiceResult.Success>()

        second.customer.id shouldBe first.customer.id
    }

    @Test
    fun `given a credit sale with a blank customer name, when executed, then it returns BlankCustomerName`() {
        openPeriod()
        seedCoa()

        val result = useCase.execute(request(SaleMethod.CREDIT, customerName = "  "))

        result.shouldBeInstanceOf<CreateSalesInvoiceResult.BlankCustomerName>()
    }

    @Test
    fun `given a non-positive amount, when executed, then it returns InvalidAmount`() {
        openPeriod()
        seedCoa()

        val result = useCase.execute(request(SaleMethod.CASH, amount = "0.00"))

        result.shouldBeInstanceOf<CreateSalesInvoiceResult.InvalidAmount>()
    }

    @Test
    fun `given no open Period for the Company, when executed, then it returns NoOpenPeriod`() {
        seedCoa()

        val result = useCase.execute(request(SaleMethod.CREDIT))

        result.shouldBeInstanceOf<CreateSalesInvoiceResult.NoOpenPeriod>()
    }

    @Test
    fun `given no Accounts Receivable control account configured, when executed a credit sale, then it returns ArControlAccountNotConfigured`() {
        openPeriod()
        account("1000", AccountType.ASSET)
        account("4000", AccountType.REVENUE)

        val result = useCase.execute(request(SaleMethod.CREDIT))

        result.shouldBeInstanceOf<CreateSalesInvoiceResult.ArControlAccountNotConfigured>()
    }

    @Test
    fun `given no Cash account configured, when executed a cash sale, then it returns CashAccountNotConfigured`() {
        openPeriod()
        account("1100", AccountType.ASSET)
        account("4000", AccountType.REVENUE)

        val result = useCase.execute(request(SaleMethod.CASH))

        result.shouldBeInstanceOf<CreateSalesInvoiceResult.CashAccountNotConfigured>()
    }

    @Test
    fun `given no Revenue account configured, when executed, then it returns RevenueAccountNotConfigured`() {
        openPeriod()
        account("1000", AccountType.ASSET)
        account("1100", AccountType.ASSET)

        val result = useCase.execute(request(SaleMethod.CASH))

        result.shouldBeInstanceOf<CreateSalesInvoiceResult.RevenueAccountNotConfigured>()
    }

    @Test
    fun `given a repeat credit sale to the same customer name, when executed, then it reuses the existing Customer and accumulates the balance`() {
        openPeriod()
        seedCoa()

        val first = useCase.execute(request(SaleMethod.CREDIT, amount = "100.00")).shouldBeInstanceOf<CreateSalesInvoiceResult.Success>()
        val second = useCase.execute(request(SaleMethod.CREDIT, amount = "50.00")).shouldBeInstanceOf<CreateSalesInvoiceResult.Success>()

        second.customer.id shouldBe first.customer.id
        second.customer.balance shouldBe Money(BigDecimal("150.00"), GBP)
    }

}
