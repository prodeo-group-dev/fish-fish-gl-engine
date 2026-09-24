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

class AssessFixedAssetImpairmentUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val fixedAssetRepository = FakeFixedAssetRepository()
    private val useCase = AssessFixedAssetImpairmentUseCase(fixedAssetRepository, periodRepository, accountRepository, journalEntryRepository)

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
            companyId, "Factory Equipment", AssetCategory.EQUIPMENT, Money(BigDecimal("10000.00"), GBP), TODAY.minusYears(1), 10
        )
        fixedAssetRepository.save(asset)
        return asset
    }

    @Test
    fun `given a recoverable amount below net book value, when executed, then it posts an impairment loss`() {
        val period = openPeriod()
        val asset = asset()
        val impairmentExpenseAccount = account("6200", AccountType.EXPENSE)
        val accumulatedImpairmentAccount = account("1220", AccountType.ASSET)

        val result = useCase.execute(
            AssessFixedAssetImpairmentUseCase.Request(
                asset.id, Money(BigDecimal("7000.00"), GBP), impairmentExpenseAccount.id, accumulatedImpairmentAccount.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<AssessFixedAssetImpairmentResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.journalEntry.lines.single { it.accountId == impairmentExpenseAccount.id }.amount shouldBe Money(BigDecimal("3000.00"), GBP)
        success.fixedAsset.accumulatedImpairmentLoss shouldBe Money(BigDecimal("3000.00"), GBP)
    }

    @Test
    fun `given a recoverable amount equal to net book value, when executed, then it returns NoChangeNeeded`() {
        val period = openPeriod()
        val asset = asset()
        val impairmentExpenseAccount = account("6200", AccountType.EXPENSE)
        val accumulatedImpairmentAccount = account("1220", AccountType.ASSET)

        val result = useCase.execute(
            AssessFixedAssetImpairmentUseCase.Request(
                asset.id, Money(BigDecimal("10000.00"), GBP), impairmentExpenseAccount.id, accumulatedImpairmentAccount.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<AssessFixedAssetImpairmentResult.NoChangeNeeded>()
    }

    @Test
    fun `given an Impairment Expense Account belonging to another Company, when executed, then it returns ImpairmentExpenseAccountNotFound`() {
        val period = openPeriod()
        val asset = asset()
        val otherCompanysImpairmentExpense = Account.create(CompanyId.generate(), AccountType.EXPENSE, null, "6200", "Test Account")
        accountRepository.save(otherCompanysImpairmentExpense)
        val accumulatedImpairmentAccount = account("1220", AccountType.ASSET)

        val result = useCase.execute(
            AssessFixedAssetImpairmentUseCase.Request(
                asset.id, Money(BigDecimal("7000.00"), GBP), otherCompanysImpairmentExpense.id, accumulatedImpairmentAccount.id, period.id, TODAY
            )
        )

        result.shouldBeInstanceOf<AssessFixedAssetImpairmentResult.ImpairmentExpenseAccountNotFound>()
    }
}
