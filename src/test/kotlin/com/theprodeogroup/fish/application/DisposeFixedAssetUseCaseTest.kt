package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 9, 3)

class DisposeFixedAssetUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val fixedAssetRepository = FakeFixedAssetRepository()
    private val useCase = DisposeFixedAssetUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)

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

    private fun asset(): FixedAsset {
        val asset = FixedAsset.create(
            companyId, "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("10000.00"), GBP), TODAY.minusYears(2), 5
        )
        fixedAssetRepository.save(asset)
        return asset
    }

    @Test
    fun `given a valid request in an Open Period, when executed, then it disposes the asset and posts a JournalEntry`() {
        val period = openPeriod()
        val asset = asset()
        val cashAccount = account("1000", AccountType.ASSET)
        val fixedAssetAccount = account("1200", AccountType.ASSET)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)
        val saleOfFixedAssetAccount = account("4900", AccountType.REVENUE)

        val result = useCase.execute(
            DisposeFixedAssetUseCase.Request(
                asset.id, Money(BigDecimal("6000.00"), GBP), cashAccount.id, fixedAssetAccount.id,
                accumulatedDepreciationAccount.id, saleOfFixedAssetAccount.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<DisposeFixedAssetResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.fixedAsset.isDisposed shouldBe true
        success.journalEntry.lines.single { it.accountId == fixedAssetAccount.id }.amount shouldBe Money(BigDecimal("10000.00"), GBP)
        success.journalEntry.lines.single { it.accountId == cashAccount.id }.amount shouldBe Money(BigDecimal("6000.00"), GBP)
    }

    @Test
    fun `given an already-disposed asset, when executed, then it returns AlreadyDisposed`() {
        val period = openPeriod()
        val asset = asset()
        val cashAccount = account("1000", AccountType.ASSET)
        val fixedAssetAccount = account("1200", AccountType.ASSET)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)
        val saleOfFixedAssetAccount = account("4900", AccountType.REVENUE)
        val request = DisposeFixedAssetUseCase.Request(
            asset.id, Money(BigDecimal("6000.00"), GBP), cashAccount.id, fixedAssetAccount.id,
            accumulatedDepreciationAccount.id, saleOfFixedAssetAccount.id, period.id, TODAY
        )
        useCase.execute(request)

        val result = useCase.execute(request)

        result.shouldBeInstanceOf<DisposeFixedAssetResult.AlreadyDisposed>()
    }

    @Test
    fun `given a positive accumulated impairment loss and no accumulated impairment account supplied, when executed, then it returns AccumulatedImpairmentAccountRequired`() {
        val period = openPeriod()
        val asset = asset()
        val cashAccount = account("1000", AccountType.ASSET)
        val fixedAssetAccount = account("1200", AccountType.ASSET)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)
        val saleOfFixedAssetAccount = account("4900", AccountType.REVENUE)
        val impairmentExpenseAccount = account("6200", AccountType.EXPENSE)
        val accumulatedImpairmentAccount = account("1220", AccountType.ASSET)
        asset.assessImpairment(Money(BigDecimal("7000.00"), GBP), impairmentExpenseAccount.id, accumulatedImpairmentAccount.id, period.id, TODAY)
        fixedAssetRepository.save(asset)

        val result = useCase.execute(
            DisposeFixedAssetUseCase.Request(
                asset.id, Money(BigDecimal("6000.00"), GBP), cashAccount.id, fixedAssetAccount.id,
                accumulatedDepreciationAccount.id, saleOfFixedAssetAccount.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<DisposeFixedAssetResult.AccumulatedImpairmentAccountRequired>()
    }

    @Test
    fun `given a Cash Account belonging to another Company, when executed, then it returns CashAccountNotFound`() {
        val period = openPeriod()
        val asset = asset()
        val otherCompanysCash = Account.create(CompanyId.generate(), AccountType.ASSET, AccountClassification.CURRENT, "1000", "Test Account")
        accountRepository.save(otherCompanysCash)
        val fixedAssetAccount = account("1200", AccountType.ASSET)
        val accumulatedDepreciationAccount = account("1210", AccountType.ASSET)
        val saleOfFixedAssetAccount = account("4900", AccountType.REVENUE)

        val result = useCase.execute(
            DisposeFixedAssetUseCase.Request(
                asset.id, Money(BigDecimal("6000.00"), GBP), otherCompanysCash.id, fixedAssetAccount.id,
                accumulatedDepreciationAccount.id, saleOfFixedAssetAccount.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<DisposeFixedAssetResult.CashAccountNotFound>()
    }
}
