package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.opening.OpeningImportBatchStatus
import com.theprodeogroup.fish.domain.opening.OpeningImportRowStatus
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
private val TODAY: LocalDate = LocalDate.of(2026, 10, 1)

/** `ImportFixedAssetsUseCase` - the second opening-figures CSV importer, reusing `CreateFixedAssetUseCase` with no new use case (design doc Section 4.2). */
class ImportFixedAssetsUseCaseTest {

    private class Fixture {
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val fixedAssetRepository = FakeFixedAssetRepository()
        val batchRepository = FakeOpeningImportBatchRepository()
        val rowResultRepository = FakeOpeningImportRowResultRepository()
        val postJournalEntryUseCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)
        val createFixedAssetUseCase = CreateFixedAssetUseCase(companyRepository, fixedAssetRepository, postJournalEntryUseCase)
        val useCase = ImportFixedAssetsUseCase(
            companyRepository, periodRepository, accountRepository, fixedAssetRepository, createFixedAssetUseCase, batchRepository, rowResultRepository
        )

        val company = Company.create(TenantId.generate(), "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP).also { companyRepository.save(it) }
        val fixedAssetAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.NON_CURRENT, "1200", "Fixed Assets")
            .also { accountRepository.save(it) }
        val suspenseAccount = Account.create(company.id, AccountType.EQUITY, null, ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE, "Suspense Account")
            .also { accountRepository.save(it) }

        init {
            Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(10), TODAY.plusDays(20)).also {
                it.open()
                periodRepository.save(it)
            }
        }

        fun request(vararg rows: ImportFixedAssetsUseCase.Row) = ImportFixedAssetsUseCase.Request(
            companyId = company.id, anchorDate = TODAY, filenameHash = "hash", createdByEmail = "finance@acme.test", rows = rows.toList()
        )

        fun row(
            rowNumber: Int = 1,
            assetName: String = "Delivery Van",
            category: String = "VEHICLES",
            acquisitionDate: LocalDate = TODAY.minusDays(5),
            cost: BigDecimal = BigDecimal("18000.00"),
            currency: String = "GBP",
            usefulLifeYears: Int? = 5,
            identifier: String? = "REG-123"
        ) = ImportFixedAssetsUseCase.Row(rowNumber, assetName, category, acquisitionDate, cost, currency, usefulLifeYears, identifier, fixedAssetAccount.code)
    }

    @Test
    fun `given an unknown Company, when validated, then it reports not found`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            ImportFixedAssetsUseCase.Request(CompanyId.generate(), TODAY, "hash", "x@test.com", emptyList())
        )

        result shouldBe ImportFixedAssetsUseCase.Result.CompanyNotFound
    }

    @Test
    fun `given a valid row, when validated (dry run), then it's ACCEPTED but nothing is posted or persisted`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(fixture.request(fixture.row()))

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.batch.status shouldBe OpeningImportBatchStatus.VALIDATED
        success.rowResults.single().status shouldBe OpeningImportRowStatus.ACCEPTED
        fixture.fixedAssetRepository.findAllByCompany(fixture.company.id) shouldBe emptyList()
        fixture.batchRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a valid row, when committed, then it posts Dr Fixed Asset Cr Suspense tagged IMPORT and persists the register entry`() {
        val fixture = Fixture()

        val result = fixture.useCase.commit(fixture.request(fixture.row()))

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.batch.status shouldBe OpeningImportBatchStatus.COMMITTED
        success.batch.acceptedCount shouldBe 1
        success.rowResults.single().resultingEntityIds.size shouldBe 1

        fixture.fixedAssetRepository.findAllByCompany(fixture.company.id).size shouldBe 1
        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.source shouldBe JournalSource.IMPORT
        posted.lines.single { it.accountId == fixture.fixedAssetAccount.id }
        posted.lines.single { it.accountId == fixture.suspenseAccount.id }
    }

    @Test
    fun `given two rows with the same identifier, when validated, then the second is rejected as a duplicate`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            fixture.request(fixture.row(rowNumber = 1, identifier = "REG-123"), fixture.row(rowNumber = 2, identifier = "REG-123"))
        )

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.rowResults[0].status shouldBe OpeningImportRowStatus.ACCEPTED
        success.rowResults[1].status shouldBe OpeningImportRowStatus.REJECTED
        success.rowResults[1].errors.single() shouldBe "duplicate identifier 'REG-123'"
    }

    @Test
    fun `given two rows with no identifier, like Land, when validated, then neither is treated as a duplicate of the other`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            fixture.request(
                fixture.row(rowNumber = 1, category = "LAND", usefulLifeYears = null, identifier = null),
                fixture.row(rowNumber = 2, category = "LAND", usefulLifeYears = null, identifier = null)
            )
        )

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.rowResults.map { it.status } shouldBe listOf(OpeningImportRowStatus.ACCEPTED, OpeningImportRowStatus.ACCEPTED)
    }

    @Test
    fun `given an identifier that already exists on a previously-committed Fixed Asset, when validated, then it's rejected`() {
        val fixture = Fixture()
        fixture.useCase.commit(fixture.request(fixture.row(identifier = "REG-999")))

        val result = fixture.useCase.validate(fixture.request(fixture.row(rowNumber = 1, identifier = "REG-999")))

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.REJECTED
    }

    @Test
    fun `given Land with a useful_life_years specified, when validated, then it's rejected`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(fixture.request(fixture.row(category = "LAND", usefulLifeYears = 10)))

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.rowResults.single().errors.single() shouldBe "Land cannot have a useful_life_years"
    }

    @Test
    fun `given a zero useful_life_years, when validated, then it's rejected`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(fixture.request(fixture.row(usefulLifeYears = 0)))

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.REJECTED
    }

    @Test
    fun `given an invalid category, when validated, then it's rejected`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(fixture.request(fixture.row(category = "FURNITURE")))

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.REJECTED
    }

    @Test
    fun `given an acquisition_date after the batch anchor date, when validated, then it's rejected`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(fixture.request(fixture.row(acquisitionDate = TODAY.plusDays(1))))

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.REJECTED
    }

    @Test
    fun `given an unknown fixed_asset_account_code, when validated, then it's rejected`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            ImportFixedAssetsUseCase.Request(
                fixture.company.id, TODAY, "hash", "finance@acme.test",
                listOf(ImportFixedAssetsUseCase.Row(1, "Van", "VEHICLES", TODAY, BigDecimal("1000.00"), "GBP", null, null, "9999"))
            )
        )

        val success = result.shouldBeInstanceOf<ImportFixedAssetsUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.REJECTED
    }
}
