package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 22)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`EcosystemRepositoryFakes.kt`. */
class PostInventoryReceiptUseCaseTest {

    private val stockItemRepository = FakeStockItemRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = PostInventoryReceiptUseCase(
        stockItemRepository, periodRepository, accountRepository, journalEntryRepository
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

    private fun stockItem(): StockItem {
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItemRepository.save(stockItem)
        return stockItem
    }

    @Test
    fun `given a valid receipt in an Open Period, when executed, then it returns a Posted JournalEntry and updates the StockItem`() {
        val period = openPeriod()
        val item = stockItem()
        val inventory = account("1300", AccountType.ASSET)
        val equity = account("3000", AccountType.EQUITY)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("10"), Money(BigDecimal("5.00"), GBP),
                inventory.id, equity.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<PostInventoryReceiptResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.stockItem.quantityOnHand shouldBe BigDecimal("10")
        success.stockItem.unitCost shouldBe Money(BigDecimal("5.00"), GBP)
    }

    @Test
    fun `given success, then the StockItem and JournalEntry are saved, and both Accounts are marked posted`() {
        val period = openPeriod()
        val item = stockItem()
        val inventory = account("1300", AccountType.ASSET)
        val equity = account("3000", AccountType.EQUITY)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("10"), Money(BigDecimal("5.00"), GBP),
                inventory.id, equity.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<PostInventoryReceiptResult.Success>()
        stockItemRepository.saveCalls shouldContain item.id
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain inventory.id
        accountRepository.saveCalls shouldContain equity.id
        requireNotNull(accountRepository.findById(inventory.id)).validateDeletion().isValid shouldBe false
    }

    @Test
    fun `given a second receipt, then the StockItem's unit cost blends into a new weighted average`() {
        val period = openPeriod()
        val item = stockItem()
        item.recordReceipt(BigDecimal("10"), Money(BigDecimal("5.00"), GBP))
        val inventory = account("1300", AccountType.ASSET)
        val equity = account("3000", AccountType.EQUITY)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("10"), Money(BigDecimal("7.00"), GBP),
                inventory.id, equity.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<PostInventoryReceiptResult.Success>()
        success.stockItem.quantityOnHand shouldBe BigDecimal("20")
        success.stockItem.unitCost shouldBe Money(BigDecimal("6.00"), GBP)
    }

    @Test
    fun `given a nonexistent StockItem id, when executed, then it returns StockItemNotFound`() {
        val period = openPeriod()
        val inventory = account("1300", AccountType.ASSET)
        val equity = account("3000", AccountType.EQUITY)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                StockItemId.generate(), BigDecimal("10"), Money(BigDecimal("5.00"), GBP),
                inventory.id, equity.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryReceiptResult.StockItemNotFound>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val item = stockItem()
        val inventory = account("1300", AccountType.ASSET)
        val equity = account("3000", AccountType.EQUITY)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("10"), Money(BigDecimal("5.00"), GBP),
                inventory.id, equity.id, PeriodId.generate(), TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryReceiptResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val item = stockItem()
        val inventory = account("1300", AccountType.ASSET)
        val equity = account("3000", AccountType.EQUITY)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("10"), Money(BigDecimal("5.00"), GBP),
                inventory.id, equity.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryReceiptResult.PeriodNotOpen>()
    }

    @Test
    fun `given an Inventory Asset Account that does not exist, when executed, then it returns InventoryAssetAccountNotFound`() {
        val period = openPeriod()
        val item = stockItem()
        val equity = account("3000", AccountType.EQUITY)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("10"), Money(BigDecimal("5.00"), GBP),
                AccountId.generate(), equity.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryReceiptResult.InventoryAssetAccountNotFound>()
    }

    @Test
    fun `given a contra Account that does not exist, when executed, then it returns ContraAccountNotFound`() {
        val period = openPeriod()
        val item = stockItem()
        val inventory = account("1300", AccountType.ASSET)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("10"), Money(BigDecimal("5.00"), GBP),
                inventory.id, AccountId.generate(), period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryReceiptResult.ContraAccountNotFound>()
    }

    @Test
    fun `given a non-positive quantity, when executed, then it returns InvalidReceipt and mutates nothing`() {
        val period = openPeriod()
        val item = stockItem()
        val inventory = account("1300", AccountType.ASSET)
        val equity = account("3000", AccountType.EQUITY)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("0"), Money(BigDecimal("5.00"), GBP),
                inventory.id, equity.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryReceiptResult.InvalidReceipt>()
        requireNotNull(stockItemRepository.findById(item.id)).quantityOnHand shouldBe BigDecimal.ZERO
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a mismatched currency, when executed, then it returns InvalidReceipt`() {
        val period = openPeriod()
        val item = stockItem()
        val inventory = account("1300", AccountType.ASSET)
        val equity = account("3000", AccountType.EQUITY)
        val usd = Currency.getInstance("USD")

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                item.id, BigDecimal("10"), Money(BigDecimal("5.00"), usd),
                inventory.id, equity.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryReceiptResult.InvalidReceipt>()
    }
}
