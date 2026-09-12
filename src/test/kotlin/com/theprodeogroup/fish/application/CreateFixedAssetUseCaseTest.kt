package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 9, 3)

/**
 * Covers acquisition posting (2026-09-12, "I posted into the Fixed
 * asset register and it did not carry through into the ledgers and
 * the balance sheet") alongside the pre-existing register-entry
 * validation this use case already had.
 */
class CreateFixedAssetUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val fixedAssetRepository = FakeFixedAssetRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
    private val useCase = CreateFixedAssetUseCase(companyRepository, fixedAssetRepository, postJournalEntryUseCase)

    private fun company(): Company {
        val company = Company.create(TenantId.generate(), "Purse UK", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        return company
    }

    private fun openPeriod(companyId: CompanyId): Period {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun account(companyId: CompanyId, code: String, type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        val account = Account.create(companyId, type, classification, code, "Test Account")
        accountRepository.save(account)
        return account
    }

    private fun cashRequest(
        company: Company,
        periodId: PeriodId,
        fixedAssetAccountId: AccountId,
        cashAccountId: AccountId,
        cost: Money = Money(BigDecimal("15000.00"), GBP),
        usefulLifeYears: Int? = 5,
        category: AssetCategory = AssetCategory.VEHICLES,
        identifier: String? = "REG-XY26 ABC"
    ) = CreateFixedAssetUseCase.Request(
        company.id, "Delivery Van", category, cost, TODAY, usefulLifeYears, identifier,
        periodId, fixedAssetAccountId, FixedAssetFundingMethod.Cash(cashAccountId)
    )

    @Test
    fun `given a cash-funded acquisition in an Open Period, when executed, then it posts Dr Fixed Asset Cr Cash tagged as an investing cash flow`() {
        val company = company()
        val period = openPeriod(company.id)
        val fixedAssetAccount = account(company.id, "1200", AccountType.ASSET)
        val cashAccount = account(company.id, "1000", AccountType.ASSET)

        val result = useCase.execute(cashRequest(company, period.id, fixedAssetAccount.id, cashAccount.id))

        val success = result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val debitLine = success.journalEntry.lines.single { it.accountId == fixedAssetAccount.id }
        val creditLine = success.journalEntry.lines.single { it.accountId == cashAccount.id }
        debitLine.amount shouldBe Money(BigDecimal("15000.00"), GBP)
        creditLine.amount shouldBe Money(BigDecimal("15000.00"), GBP)
        creditLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.INVESTING.name
        success.fixedAsset.identifier shouldBe "REG-XY26 ABC"
        fixedAssetRepository.saveCalls shouldBe listOf(success.fixedAsset.id)
        journalEntryRepository.saveCalls shouldBe listOf(success.journalEntry.id)
    }

    @Test
    fun `given an on-account acquisition, when executed, then it posts Dr Fixed Asset Cr AP Control tagged with the vendor reference`() {
        val company = company()
        val period = openPeriod(company.id)
        val fixedAssetAccount = account(company.id, "1200", AccountType.ASSET)
        val apControlAccount = account(company.id, "2100", AccountType.LIABILITY)

        val result = useCase.execute(
            CreateFixedAssetUseCase.Request(
                company.id, "Warehouse Racking", AssetCategory.EQUIPMENT, Money(BigDecimal("8000.00"), GBP), TODAY, 8, "SN-88213",
                period.id, fixedAssetAccount.id, FixedAssetFundingMethod.OnAccount(apControlAccount.id, "Acme Racking Ltd - Invoice 4471")
            )
        )

        val success = result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.Success>()
        val creditLine = success.journalEntry.lines.single { it.accountId == apControlAccount.id }
        creditLine.dimensions[DimensionType.VENDOR] shouldBe "Acme Racking Ltd - Invoice 4471"
    }

    @Test
    fun `given an already-owned acquisition, when executed, then it posts Dr Fixed Asset Cr Suspense Account with no dimension tag`() {
        val company = company()
        val period = openPeriod(company.id)
        val fixedAssetAccount = account(company.id, "1200", AccountType.ASSET)
        val suspenseAccount = account(company.id, "3910", AccountType.EQUITY)

        val result = useCase.execute(
            CreateFixedAssetUseCase.Request(
                company.id, "Car", AssetCategory.VEHICLES, Money(BigDecimal("5000.00"), GBP), TODAY, 5, "REG-CAR01",
                period.id, fixedAssetAccount.id, FixedAssetFundingMethod.AlreadyOwned(suspenseAccount.id)
            )
        )

        val success = result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.Success>()
        val debitLine = success.journalEntry.lines.single { it.accountId == fixedAssetAccount.id }
        val creditLine = success.journalEntry.lines.single { it.accountId == suspenseAccount.id }
        debitLine.amount shouldBe Money(BigDecimal("5000.00"), GBP)
        creditLine.amount shouldBe Money(BigDecimal("5000.00"), GBP)
        creditLine.dimensions shouldBe emptyMap()
    }

    @Test
    fun `given an already-owned acquisition with a missing Suspense Account, when executed, then it returns AccountNotFound and saves no FixedAsset`() {
        val company = company()
        val period = openPeriod(company.id)
        val fixedAssetAccount = account(company.id, "1200", AccountType.ASSET)

        val result = useCase.execute(
            CreateFixedAssetUseCase.Request(
                company.id, "Car", AssetCategory.VEHICLES, Money(BigDecimal("5000.00"), GBP), TODAY, 5, "REG-CAR01",
                period.id, fixedAssetAccount.id, FixedAssetFundingMethod.AlreadyOwned(AccountId.generate())
            )
        )

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.AccountNotFound>()
        fixedAssetRepository.saveCalls shouldBe emptyList()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a nonexistent Company, when executed, then it returns CompanyNotFound`() {
        val result = useCase.execute(
            CreateFixedAssetUseCase.Request(
                CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("15000.00"), GBP), TODAY, 5, null,
                PeriodId.generate(), AccountId.generate(), FixedAssetFundingMethod.Cash(AccountId.generate())
            )
        )

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.CompanyNotFound>()
    }

    @Test
    fun `given a non-positive cost, when executed, then it returns InvalidFixedAsset and posts nothing`() {
        val company = company()
        val period = openPeriod(company.id)
        val fixedAssetAccount = account(company.id, "1200", AccountType.ASSET)
        val cashAccount = account(company.id, "1000", AccountType.ASSET)

        val result = useCase.execute(cashRequest(company, period.id, fixedAssetAccount.id, cashAccount.id, cost = Money(BigDecimal.ZERO, GBP)))

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.InvalidFixedAsset>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given Land with a useful life, when executed, then it returns InvalidFixedAsset`() {
        val company = company()
        val period = openPeriod(company.id)
        val fixedAssetAccount = account(company.id, "1200", AccountType.ASSET)
        val cashAccount = account(company.id, "1000", AccountType.ASSET)

        val result = useCase.execute(
            cashRequest(company, period.id, fixedAssetAccount.id, cashAccount.id, category = AssetCategory.LAND, usefulLifeYears = 10)
        )

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.InvalidFixedAsset>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen and saves no FixedAsset`() {
        val company = company()
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val fixedAssetAccount = account(company.id, "1200", AccountType.ASSET)
        val cashAccount = account(company.id, "1000", AccountType.ASSET)

        val result = useCase.execute(cashRequest(company, period.id, fixedAssetAccount.id, cashAccount.id))

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.PeriodNotOpen>()
        fixedAssetRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a cash Account that does not exist, when executed, then it returns AccountNotFound and saves no FixedAsset`() {
        val company = company()
        val period = openPeriod(company.id)
        val fixedAssetAccount = account(company.id, "1200", AccountType.ASSET)

        val result = useCase.execute(cashRequest(company, period.id, fixedAssetAccount.id, AccountId.generate()))

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.AccountNotFound>()
        fixedAssetRepository.saveCalls shouldBe emptyList()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }
}
