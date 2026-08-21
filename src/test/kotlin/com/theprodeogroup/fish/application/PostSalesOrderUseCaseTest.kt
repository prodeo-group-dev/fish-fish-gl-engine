package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.sales.Customer
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.sales.SalesOrder
import com.theprodeogroup.fish.domain.sales.SalesOrderId
import com.theprodeogroup.fish.domain.sales.SalesOrderLine
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 21)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`EcosystemRepositoryFakes.kt`. */
class PostSalesOrderUseCaseTest {

    private val salesOrderRepository = FakeSalesOrderRepository()
    private val customerRepository = FakeCustomerRepository()
    private val stockItemRepository = FakeStockItemRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = PostSalesOrderUseCase(
        salesOrderRepository, customerRepository, stockItemRepository,
        periodRepository, accountRepository, journalEntryRepository
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

    private fun customer(): Customer {
        val customer = Customer.create(companyId, "Acme Buyers", GBP)
        customerRepository.save(customer)
        return customer
    }

    private fun serviceOrder(customerId: CustomerId, revenueAccount: Account): SalesOrder {
        val order = SalesOrder.create(
            companyId, customerId, TODAY,
            listOf(SalesOrderLine("Consulting", revenueAccount.id, Money(BigDecimal("500.00"), GBP), LineItemType.SERVICE))
        )
        salesOrderRepository.save(order)
        return order
    }

    @Test
    fun `given a Draft SalesOrder line in an Open Period, when executed, then it returns a Posted JournalEntry and updates the Customer balance`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val order = serviceOrder(buyer.id, revenue)

        val result = useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id))

        val success = result.shouldBeInstanceOf<PostSalesOrderResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.customer.balance shouldBe Money(BigDecimal("500.00"), GBP)
        success.salesOrder.status.name shouldBe "FULFILLED"
    }

    @Test
    fun `given success, then the order, customer, and JournalEntry are all saved`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val order = serviceOrder(buyer.id, revenue)

        val result = useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id))

        val success = result.shouldBeInstanceOf<PostSalesOrderResult.Success>()
        salesOrderRepository.saveCalls shouldContain order.id
        customerRepository.saveCalls shouldContain buyer.id
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
    }

    @Test
    fun `given success, then the revenue and AR control Accounts are marked posted and saved`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val order = serviceOrder(buyer.id, revenue)

        useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id))

        accountRepository.saveCalls shouldContain revenue.id
        accountRepository.saveCalls shouldContain arControl.id
        requireNotNull(accountRepository.findById(revenue.id)).validateDeletion().isValid shouldBe false
    }

    @Test
    fun `given a GOODS line, when executed, then the referenced StockItem is issued and COGS posts alongside`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val cogsExpense = account("5010", AccountType.EXPENSE)
        val inventoryAsset = account("1300", AccountType.ASSET)
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItem.recordReceipt(BigDecimal("10"), Money(BigDecimal("50.00"), GBP))
        stockItemRepository.save(stockItem)
        val order = SalesOrder.create(
            companyId, buyer.id, TODAY,
            listOf(
                SalesOrderLine(
                    "3 Widgets", revenue.id, Money(BigDecimal("150.00"), GBP),
                    LineItemType.GOODS, BigDecimal("3"), stockItem.id
                )
            )
        )
        salesOrderRepository.save(order)

        val result = useCase.execute(
            PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id, cogsExpense.id, inventoryAsset.id)
        )

        result.shouldBeInstanceOf<PostSalesOrderResult.Success>()
        stockItemRepository.saveCalls shouldContain stockItem.id
        requireNotNull(stockItemRepository.findById(stockItem.id)).quantityOnHand shouldBe BigDecimal("7")
        accountRepository.saveCalls shouldContain cogsExpense.id
        accountRepository.saveCalls shouldContain inventoryAsset.id
    }

    @Test
    fun `given a nonexistent SalesOrder id, when executed, then it returns SalesOrderNotFound`() {
        val period = openPeriod()
        val arControl = account("1200", AccountType.ASSET)

        val result = useCase.execute(PostSalesOrderUseCase.Request(SalesOrderId.generate(), 0, period.id, arControl.id))

        result.shouldBeInstanceOf<PostSalesOrderResult.SalesOrderNotFound>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val order = serviceOrder(buyer.id, revenue)

        val result = useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, PeriodId.generate(), arControl.id))

        result.shouldBeInstanceOf<PostSalesOrderResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val order = serviceOrder(buyer.id, revenue)

        val result = useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id))

        result.shouldBeInstanceOf<PostSalesOrderResult.PeriodNotOpen>()
    }

    @Test
    fun `given an AR control Account that does not exist, when executed, then it returns ArControlAccountNotFound`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val order = serviceOrder(buyer.id, revenue)

        val result = useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, period.id, AccountId.generate()))

        result.shouldBeInstanceOf<PostSalesOrderResult.ArControlAccountNotFound>()
    }

    @Test
    fun `given an out-of-range line index, when executed, then it returns InvalidLineIndex`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val order = serviceOrder(buyer.id, revenue)

        val result = useCase.execute(PostSalesOrderUseCase.Request(order.id, 5, period.id, arControl.id))

        result.shouldBeInstanceOf<PostSalesOrderResult.InvalidLineIndex>()
    }

    @Test
    fun `given a line already delivered, when executed again, then it returns LineAlreadyDelivered`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val order = serviceOrder(buyer.id, revenue)
        useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id))

        val result = useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id))

        result.shouldBeInstanceOf<PostSalesOrderResult.LineAlreadyDelivered>()
    }

    @Test
    fun `given a GOODS line with no inventory accounts supplied, when executed, then it returns GoodsLineRequiresInventoryAccounts`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItem.recordReceipt(BigDecimal("10"), Money(BigDecimal("50.00"), GBP))
        stockItemRepository.save(stockItem)
        val order = SalesOrder.create(
            companyId, buyer.id, TODAY,
            listOf(
                SalesOrderLine(
                    "3 Widgets", revenue.id, Money(BigDecimal("150.00"), GBP),
                    LineItemType.GOODS, BigDecimal("3"), stockItem.id
                )
            )
        )
        salesOrderRepository.save(order)

        val result = useCase.execute(PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id))

        result.shouldBeInstanceOf<PostSalesOrderResult.GoodsLineRequiresInventoryAccounts>()
    }

    @Test
    fun `given a GOODS line and insufficient stock, when executed, then it returns InsufficientStock`() {
        val period = openPeriod()
        val buyer = customer()
        val revenue = account("4000", AccountType.REVENUE)
        val arControl = account("1200", AccountType.ASSET)
        val cogsExpense = account("5010", AccountType.EXPENSE)
        val inventoryAsset = account("1300", AccountType.ASSET)
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItem.recordReceipt(BigDecimal("1"), Money(BigDecimal("50.00"), GBP))
        stockItemRepository.save(stockItem)
        val order = SalesOrder.create(
            companyId, buyer.id, TODAY,
            listOf(
                SalesOrderLine(
                    "3 Widgets", revenue.id, Money(BigDecimal("150.00"), GBP),
                    LineItemType.GOODS, BigDecimal("3"), stockItem.id
                )
            )
        )
        salesOrderRepository.save(order)

        val result = useCase.execute(
            PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id, cogsExpense.id, inventoryAsset.id)
        )

        result.shouldBeInstanceOf<PostSalesOrderResult.InsufficientStock>()
    }
}
