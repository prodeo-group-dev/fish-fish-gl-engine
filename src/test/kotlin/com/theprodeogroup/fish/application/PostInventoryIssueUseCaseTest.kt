package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
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
private val TODAY = LocalDate.of(2026, 8, 23)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`EcosystemRepositoryFakes.kt`. */
class PostInventoryIssueUseCaseTest {

    private val stockItemRepository = FakeStockItemRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = PostInventoryIssueUseCase(
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

    private fun stockItemWithStock(): StockItem {
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItem.recordReceipt(BigDecimal("20"), Money(BigDecimal("5.00"), GBP))
        stockItemRepository.save(stockItem)
        return stockItem
    }

    @Test
    fun `given a valid issue in an Open Period, when executed, then it returns a Posted JournalEntry and updates the StockItem`() {
        val period = openPeriod()
        val item = stockItemWithStock()
        val inventory = account("1300", AccountType.ASSET)
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("6"), inventory.id, shrinkageExpense.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<PostInventoryIssueResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.stockItem.quantityOnHand shouldBe BigDecimal("14")
        success.stockItem.unitCost shouldBe Money(BigDecimal("5.00"), GBP)
    }

    @Test
    fun `given success, then the StockItem and JournalEntry are saved, and both Accounts are marked posted`() {
        val period = openPeriod()
        val item = stockItemWithStock()
        val inventory = account("1300", AccountType.ASSET)
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("6"), inventory.id, shrinkageExpense.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<PostInventoryIssueResult.Success>()
        stockItemRepository.saveCalls shouldContain item.id
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain inventory.id
        accountRepository.saveCalls shouldContain shrinkageExpense.id
        requireNotNull(accountRepository.findById(inventory.id)).validateDeletion().isValid shouldBe false
    }

    @Test
    fun `given success, then the JournalEntry debits the contra Account and credits Inventory for the issued cost`() {
        val period = openPeriod()
        val item = stockItemWithStock()
        val inventory = account("1300", AccountType.ASSET)
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("6"), inventory.id, shrinkageExpense.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<PostInventoryIssueResult.Success>()
        val expenseLine = success.journalEntry.lines.single { it.accountId == shrinkageExpense.id }
        val inventoryLine = success.journalEntry.lines.single { it.accountId == inventory.id }
        expenseLine.amount shouldBe Money(BigDecimal("30.00"), GBP)
        inventoryLine.amount shouldBe Money(BigDecimal("30.00"), GBP)
    }

    @Test
    fun `given a nonexistent StockItem id, when executed, then it returns StockItemNotFound`() {
        val period = openPeriod()
        val inventory = account("1300", AccountType.ASSET)
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                StockItemId.generate(), BigDecimal("6"), inventory.id, shrinkageExpense.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryIssueResult.StockItemNotFound>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val item = stockItemWithStock()
        val inventory = account("1300", AccountType.ASSET)
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("6"), inventory.id, shrinkageExpense.id, PeriodId.generate(), TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryIssueResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val item = stockItemWithStock()
        val inventory = account("1300", AccountType.ASSET)
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("6"), inventory.id, shrinkageExpense.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryIssueResult.PeriodNotOpen>()
    }

    @Test
    fun `given an Inventory Asset Account that does not exist, when executed, then it returns InventoryAssetAccountNotFound`() {
        val period = openPeriod()
        val item = stockItemWithStock()
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("6"), AccountId.generate(), shrinkageExpense.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryIssueResult.InventoryAssetAccountNotFound>()
    }

    @Test
    fun `given a contra Account that does not exist, when executed, then it returns ContraAccountNotFound`() {
        val period = openPeriod()
        val item = stockItemWithStock()
        val inventory = account("1300", AccountType.ASSET)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("6"), inventory.id, AccountId.generate(), period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryIssueResult.ContraAccountNotFound>()
    }

    @Test
    fun `given a non-positive quantity, when executed, then it returns InvalidIssue and mutates nothing`() {
        val period = openPeriod()
        val item = stockItemWithStock()
        val inventory = account("1300", AccountType.ASSET)
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("0"), inventory.id, shrinkageExpense.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryIssueResult.InvalidIssue>()
        requireNotNull(stockItemRepository.findById(item.id)).quantityOnHand shouldBe BigDecimal("20")
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a quantity exceeding stock on hand, when executed, then it returns InvalidIssue`() {
        val period = openPeriod()
        val item = stockItemWithStock()
        val inventory = account("1300", AccountType.ASSET)
        val shrinkageExpense = account("5090", AccountType.EXPENSE)

        val result = useCase.execute(
            PostInventoryIssueUseCase.Request(
                item.id, BigDecimal("100"), inventory.id, shrinkageExpense.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<PostInventoryIssueResult.InvalidIssue>()
    }
}
