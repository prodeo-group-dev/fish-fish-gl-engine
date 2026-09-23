package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.fixedassets.FixedAssetId
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
private val TODAY = LocalDate.of(2026, 9, 3)

class RecordFixedAssetDepreciationUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val fixedAssetRepository = FakeFixedAssetRepository()
    private val useCase = RecordFixedAssetDepreciationUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)

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

    private fun asset(usefulLifeYears: Int? = 5): FixedAsset {
        val asset = FixedAsset.create(
            companyId, "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("10000.00"), GBP), TODAY.minusYears(1), usefulLifeYears
        )
        fixedAssetRepository.save(asset)
        return asset
    }

    @Test
    fun `given a valid request in an Open Period, when executed, then it posts a JournalEntry debiting Depreciation Expense and crediting Accumulated Depreciation`() {
        val period = openPeriod()
        val asset = asset()
        val expenseAccount = account("6100", AccountType.EXPENSE)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)

        val result = useCase.execute(
            RecordFixedAssetDepreciationUseCase.Request(asset.id, expenseAccount.id, accumulatedDepreciationAccount.id, period.id, TODAY)
        )

        val success = result.shouldBeInstanceOf<RecordFixedAssetDepreciationResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.journalEntry.lines.single { it.accountId == expenseAccount.id }.amount shouldBe Money(BigDecimal("2000.00"), GBP)
        success.journalEntry.lines.single { it.accountId == accumulatedDepreciationAccount.id }.amount shouldBe Money(BigDecimal("2000.00"), GBP)
        success.fixedAsset.accumulatedDepreciation shouldBe Money(BigDecimal("2000.00"), GBP)
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        fixedAssetRepository.saveCalls shouldContain asset.id
    }

    @Test
    fun `given a FixedAsset with no useful life, when executed, then it returns NoChangeNeeded`() {
        val period = openPeriod()
        val asset = asset(usefulLifeYears = null)
        val expenseAccount = account("6100", AccountType.EXPENSE)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)

        val result = useCase.execute(
            RecordFixedAssetDepreciationUseCase.Request(asset.id, expenseAccount.id, accumulatedDepreciationAccount.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RecordFixedAssetDepreciationResult.NoChangeNeeded>()
    }

    @Test
    fun `given a nonexistent FixedAsset id, when executed, then it returns FixedAssetNotFound`() {
        val period = openPeriod()
        val expenseAccount = account("6100", AccountType.EXPENSE)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)

        val result = useCase.execute(
            RecordFixedAssetDepreciationUseCase.Request(FixedAssetId.generate(), expenseAccount.id, accumulatedDepreciationAccount.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RecordFixedAssetDepreciationResult.FixedAssetNotFound>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val asset = asset()
        val expenseAccount = account("6100", AccountType.EXPENSE)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)

        val result = useCase.execute(
            RecordFixedAssetDepreciationUseCase.Request(asset.id, expenseAccount.id, accumulatedDepreciationAccount.id, PeriodId.generate(), TODAY)
        )

        result.shouldBeInstanceOf<RecordFixedAssetDepreciationResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val asset = asset()
        val expenseAccount = account("6100", AccountType.EXPENSE)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)

        val result = useCase.execute(
            RecordFixedAssetDepreciationUseCase.Request(asset.id, expenseAccount.id, accumulatedDepreciationAccount.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RecordFixedAssetDepreciationResult.PeriodNotOpen>()
    }

    @Test
    fun `given a Depreciation Expense Account that does not exist, when executed, then it returns DepreciationExpenseAccountNotFound`() {
        val period = openPeriod()
        val asset = asset()
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)

        val result = useCase.execute(
            RecordFixedAssetDepreciationUseCase.Request(asset.id, AccountId.generate(), accumulatedDepreciationAccount.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RecordFixedAssetDepreciationResult.DepreciationExpenseAccountNotFound>()
    }

    @Test
    fun `given an Accumulated Depreciation Account that does not exist, when executed, then it returns AccumulatedDepreciationAccountNotFound`() {
        val period = openPeriod()
        val asset = asset()
        val expenseAccount = account("6100", AccountType.EXPENSE)

        val result = useCase.execute(
            RecordFixedAssetDepreciationUseCase.Request(asset.id, expenseAccount.id, AccountId.generate(), period.id, TODAY)
        )

        result.shouldBeInstanceOf<RecordFixedAssetDepreciationResult.AccumulatedDepreciationAccountNotFound>()
    }

    @Test
    fun `given an Accumulated Depreciation Account belonging to another Company, when executed, then it returns AccumulatedDepreciationAccountNotFound`() {
        val period = openPeriod()
        val asset = asset()
        val expenseAccount = account("6100", AccountType.EXPENSE)
        val otherCompanysAccumulatedDepreciation = Account.create(CompanyId.generate(), AccountType.ASSET, AccountClassification.CURRENT, "1210", "Test Account")
        accountRepository.save(otherCompanysAccumulatedDepreciation)

        val result = useCase.execute(
            RecordFixedAssetDepreciationUseCase.Request(asset.id, expenseAccount.id, otherCompanysAccumulatedDepreciation.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RecordFixedAssetDepreciationResult.AccumulatedDepreciationAccountNotFound>()
    }
}
