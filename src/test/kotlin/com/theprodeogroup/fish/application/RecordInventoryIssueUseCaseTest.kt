package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
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
private val TODAY = LocalDate.of(2026, 8, 21)

/** The mirror image of [RecordInventoryReceiptUseCaseTest]. */
class RecordInventoryIssueUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordInventoryIssueUseCase(periodRepository, accountRepository, journalEntryRepository)

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

    private fun request(
        periodId: PeriodId,
        contraAccountId: AccountId,
        inventoryAssetAccountId: AccountId,
        committedCost: String = "500.00",
        itemId: StockItemId = StockItemId.generate()
    ) = RecordInventoryIssueUseCase.Request(
        periodId, TODAY, contraAccountId, inventoryAssetAccountId, Money(BigDecimal(committedCost), GBP), itemId,
        "Issue of Refined White Sugar against Sales Order"
    )

    @Test
    fun `given a valid request in an Open Period, when executed, then it posts a JournalEntry debiting the contra account and crediting Inventory Asset`() {
        val period = openPeriod()
        val cogs = account("5000", AccountType.EXPENSE)
        val inventoryAsset = account("1300", AccountType.ASSET)
        val itemId = StockItemId.generate()

        val result = useCase.execute(request(period.id, cogs.id, inventoryAsset.id, itemId = itemId))

        val success = result.shouldBeInstanceOf<RecordInventoryIssueResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val cogsLine = success.journalEntry.lines.single { it.accountId == cogs.id }
        val inventoryLine = success.journalEntry.lines.single { it.accountId == inventoryAsset.id }
        cogsLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        inventoryLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        inventoryLine.dimensions[DimensionType.ITEM] shouldBe itemId.value.toString()
    }

    @Test
    fun `given success, then the JournalEntry is saved and both Accounts are marked posted and saved`() {
        val period = openPeriod()
        val cogs = account("5000", AccountType.EXPENSE)
        val inventoryAsset = account("1300", AccountType.ASSET)

        val result = useCase.execute(request(period.id, cogs.id, inventoryAsset.id))

        val success = result.shouldBeInstanceOf<RecordInventoryIssueResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain cogs.id
        accountRepository.saveCalls shouldContain inventoryAsset.id
    }

    @Test
    fun `given a non-positive committed cost, when executed, then it returns InvalidAmount`() {
        val period = openPeriod()
        val cogs = account("5000", AccountType.EXPENSE)
        val inventoryAsset = account("1300", AccountType.ASSET)

        val result = useCase.execute(request(period.id, cogs.id, inventoryAsset.id, committedCost = "0.00"))

        result.shouldBeInstanceOf<RecordInventoryIssueResult.InvalidAmount>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val cogs = account("5000", AccountType.EXPENSE)
        val inventoryAsset = account("1300", AccountType.ASSET)

        val result = useCase.execute(request(PeriodId.generate(), cogs.id, inventoryAsset.id))

        result.shouldBeInstanceOf<RecordInventoryIssueResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val cogs = account("5000", AccountType.EXPENSE)
        val inventoryAsset = account("1300", AccountType.ASSET)

        val result = useCase.execute(request(period.id, cogs.id, inventoryAsset.id))

        result.shouldBeInstanceOf<RecordInventoryIssueResult.PeriodNotOpen>()
    }

    @Test
    fun `given a contra Account that does not exist, when executed, then it returns ContraAccountNotFound`() {
        val period = openPeriod()
        val inventoryAsset = account("1300", AccountType.ASSET)

        val result = useCase.execute(request(period.id, AccountId.generate(), inventoryAsset.id))

        result.shouldBeInstanceOf<RecordInventoryIssueResult.ContraAccountNotFound>()
    }

    @Test
    fun `given an Inventory Asset Account that does not exist, when executed, then it returns InventoryAssetAccountNotFound`() {
        val period = openPeriod()
        val cogs = account("5000", AccountType.EXPENSE)

        val result = useCase.execute(request(period.id, cogs.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordInventoryIssueResult.InventoryAssetAccountNotFound>()
    }
}
