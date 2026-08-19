package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.purchasing.Creditor
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrder
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderId
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderLine
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderStatus
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 20)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`EcosystemRepositoryFakes.kt`. */
class PostPurchaseOrderUseCaseTest {

    private val purchaseOrderRepository = FakePurchaseOrderRepository()
    private val creditorRepository = FakeCreditorRepository()
    private val stockItemRepository = FakeStockItemRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = PostPurchaseOrderUseCase(
        purchaseOrderRepository, creditorRepository, stockItemRepository,
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

    private fun creditor(): Creditor {
        val creditor = Creditor.create(companyId, "Acme Supplies", GBP)
        creditorRepository.save(creditor)
        return creditor
    }

    private fun serviceOrder(creditorId: CreditorId, expenseAccount: Account): PurchaseOrder {
        val order = PurchaseOrder.create(
            companyId, creditorId, TODAY,
            listOf(PurchaseOrderLine("Consulting", expenseAccount.id, Money(BigDecimal("500.00"), GBP), LineItemType.SERVICE))
        )
        purchaseOrderRepository.save(order)
        return order
    }

    @Test
    fun `given a Draft PurchaseOrder in an Open Period, when executed, then it returns a Sent order with a Posted JournalEntry`() {
        val period = openPeriod()
        val supplier = creditor()
        val expense = account("5000", AccountType.EXPENSE)
        val apControl = account("2100", AccountType.LIABILITY)
        val order = serviceOrder(supplier.id, expense)

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, apControl.id))

        val success = result.shouldBeInstanceOf<PostPurchaseOrderResult.Success>()
        success.purchaseOrder.status shouldBe PurchaseOrderStatus.SENT
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.creditor.balance shouldBe Money(BigDecimal("500.00"), GBP)
    }

    @Test
    fun `given success, then the order, creditor, and JournalEntry are all saved`() {
        val period = openPeriod()
        val supplier = creditor()
        val expense = account("5000", AccountType.EXPENSE)
        val apControl = account("2100", AccountType.LIABILITY)
        val order = serviceOrder(supplier.id, expense)

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, apControl.id))

        val success = result.shouldBeInstanceOf<PostPurchaseOrderResult.Success>()
        purchaseOrderRepository.saveCalls shouldContain order.id
        creditorRepository.saveCalls shouldContain supplier.id
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
    }

    @Test
    fun `given success, then every line Account and the AP control Account are marked posted and saved`() {
        val period = openPeriod()
        val supplier = creditor()
        val expense = account("5000", AccountType.EXPENSE)
        val apControl = account("2100", AccountType.LIABILITY)
        val order = serviceOrder(supplier.id, expense)

        useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, apControl.id))

        accountRepository.saveCalls shouldContain expense.id
        accountRepository.saveCalls shouldContain apControl.id
        requireNotNull(accountRepository.findById(expense.id)).validateDeletion().isValid shouldBe false
    }

    @Test
    fun `given a GOODS line, when executed, then the referenced StockItem receives stock and is saved`() {
        val period = openPeriod()
        val supplier = creditor()
        val inventory = account("1300", AccountType.ASSET)
        val apControl = account("2100", AccountType.LIABILITY)
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItemRepository.save(stockItem)
        val order = PurchaseOrder.create(
            companyId, supplier.id, TODAY,
            listOf(
                PurchaseOrderLine(
                    "10 Widgets", inventory.id, Money(BigDecimal("100.00"), GBP),
                    LineItemType.GOODS, BigDecimal("10"), stockItem.id
                )
            )
        )
        purchaseOrderRepository.save(order)

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, apControl.id))

        result.shouldBeInstanceOf<PostPurchaseOrderResult.Success>()
        stockItemRepository.saveCalls shouldContain stockItem.id
        requireNotNull(stockItemRepository.findById(stockItem.id)).quantityOnHand shouldBe BigDecimal("10")
    }

    @Test
    fun `given a nonexistent PurchaseOrder id, when executed, then it returns PurchaseOrderNotFound`() {
        val period = openPeriod()
        val apControl = account("2100", AccountType.LIABILITY)

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(PurchaseOrderId.generate(), period.id, apControl.id))

        result.shouldBeInstanceOf<PostPurchaseOrderResult.PurchaseOrderNotFound>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val supplier = creditor()
        val expense = account("5000", AccountType.EXPENSE)
        val apControl = account("2100", AccountType.LIABILITY)
        val order = serviceOrder(supplier.id, expense)

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(order.id, PeriodId.generate(), apControl.id))

        result.shouldBeInstanceOf<PostPurchaseOrderResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val supplier = creditor()
        val expense = account("5000", AccountType.EXPENSE)
        val apControl = account("2100", AccountType.LIABILITY)
        val order = serviceOrder(supplier.id, expense)

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, apControl.id))

        result.shouldBeInstanceOf<PostPurchaseOrderResult.PeriodNotOpen>()
    }

    @Test
    fun `given an AP control Account that does not exist, when executed, then it returns ApControlAccountNotFound`() {
        val period = openPeriod()
        val supplier = creditor()
        val expense = account("5000", AccountType.EXPENSE)
        val order = serviceOrder(supplier.id, expense)

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, AccountId.generate()))

        result.shouldBeInstanceOf<PostPurchaseOrderResult.ApControlAccountNotFound>()
    }

    @Test
    fun `given a PurchaseOrder already Sent, when executed again, then it returns PurchaseOrderNotDraft`() {
        val period = openPeriod()
        val supplier = creditor()
        val expense = account("5000", AccountType.EXPENSE)
        val apControl = account("2100", AccountType.LIABILITY)
        val order = serviceOrder(supplier.id, expense)
        useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, apControl.id))

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, apControl.id))

        result.shouldBeInstanceOf<PostPurchaseOrderResult.PurchaseOrderNotDraft>()
    }
}
