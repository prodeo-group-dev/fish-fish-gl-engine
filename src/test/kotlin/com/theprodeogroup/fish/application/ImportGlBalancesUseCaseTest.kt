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

/**
 * `ImportGlBalancesUseCase` - the first opening-figures CSV importer
 * (`docs/Opening_Figures_CSV_Upload_DDD_Design.md`), covering the
 * Suspense-redirect mechanism (Section 2) and the per-row validation
 * rules (Section 1). Deliberately does not test Inventory redirection -
 * see the use case's own KDoc for why that one category stays
 * unresolved by this increment.
 */
class ImportGlBalancesUseCaseTest {

    private class Fixture {
        val companyRepository = FakeCompanyRepository()
        val accountRepository = FakeAccountRepository()
        val periodRepository = FakePeriodRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val batchRepository = FakeOpeningImportBatchRepository()
        val rowResultRepository = FakeOpeningImportRowResultRepository()
        val recordOpeningBalanceUseCase = RecordOpeningBalanceUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)
        val useCase = ImportGlBalancesUseCase(companyRepository, accountRepository, recordOpeningBalanceUseCase, batchRepository, rowResultRepository)

        val company = Company.create(TenantId.generate(), "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP).also { companyRepository.save(it) }

        val cashAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, ChartOfAccountsTemplate.CASH_CODE, "Cash")
            .also { accountRepository.save(it) }
        val loanAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.NON_CURRENT, "2100", "Bank Loan")
            .also { accountRepository.save(it) }
        val arAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable")
            .also { accountRepository.save(it) }
        val apAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "Accounts Payable")
            .also { accountRepository.save(it) }
        val fixedAssetAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.NON_CURRENT, "1200", "Fixed Assets")
            .also { accountRepository.save(it) }
        val openingBalanceEquity = Account.create(company.id, AccountType.EQUITY, null, ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE, "Opening Balance Equity")
            .also { accountRepository.save(it) }
        val suspenseAccount = Account.create(company.id, AccountType.EQUITY, null, ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE, "Suspense Account")
            .also { accountRepository.save(it) }

        init {
            Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(10), TODAY.plusDays(20)).also {
                it.open()
                periodRepository.save(it)
            }
        }

        fun request(vararg rows: ImportGlBalancesUseCase.Row) = ImportGlBalancesUseCase.Request(
            companyId = company.id,
            anchorDate = TODAY,
            filenameHash = "hash123",
            createdByEmail = "finance@acme.test",
            rows = rows.toList()
        )
    }

    @Test
    fun `given an unknown Company, when validated, then it reports not found`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            ImportGlBalancesUseCase.Request(CompanyId.generate(), TODAY, "hash", "x@test.com", emptyList())
        )

        result shouldBe ImportGlBalancesUseCase.Result.CompanyNotFound
    }

    @Test
    fun `given a plain Asset account row, when validated (dry run), then it's classified ACCEPTED but nothing is posted or persisted`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            fixture.request(ImportGlBalancesUseCase.Row(1, fixture.cashAccount.code, BigDecimal("5000.00")))
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        success.batch.status shouldBe OpeningImportBatchStatus.VALIDATED
        success.rowResults.single().status shouldBe OpeningImportRowStatus.ACCEPTED
        success.rowResults.single().resultingEntityIds shouldBe emptyList()
        fixture.journalEntryRepository.findAllByCompany(fixture.company.id) shouldBe emptyList()
        fixture.batchRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a plain Asset account row, when committed, then it posts via RecordOpeningBalanceUseCase tagged IMPORT and persists the batch`() {
        val fixture = Fixture()

        val result = fixture.useCase.commit(
            fixture.request(ImportGlBalancesUseCase.Row(1, fixture.cashAccount.code, BigDecimal("5000.00")))
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        success.batch.status shouldBe OpeningImportBatchStatus.COMMITTED
        success.batch.acceptedCount shouldBe 1
        val rowResult = success.rowResults.single()
        rowResult.status shouldBe OpeningImportRowStatus.ACCEPTED
        rowResult.resultingEntityIds.size shouldBe 1

        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.source shouldBe JournalSource.IMPORT
        posted.lines.single { it.accountId == fixture.cashAccount.id }
        posted.lines.single { it.accountId == fixture.openingBalanceEquity.id }
    }

    @Test
    fun `given a row targeting the AR control account, when committed, then it's redirected to Suspense and marked NEEDS_ITEMIZATION`() {
        val fixture = Fixture()

        val result = fixture.useCase.commit(
            fixture.request(ImportGlBalancesUseCase.Row(1, fixture.arAccount.code, BigDecimal("1200.00")))
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.NEEDS_ITEMIZATION
        success.batch.needsItemizationCount shouldBe 1

        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.lines.any { it.accountId == fixture.arAccount.id } shouldBe false
        posted.lines.single { it.accountId == fixture.suspenseAccount.id }
    }

    @Test
    fun `given a row targeting the AP control account, when committed, then it's redirected to Suspense too`() {
        val fixture = Fixture()

        val result = fixture.useCase.commit(
            fixture.request(ImportGlBalancesUseCase.Row(1, fixture.apAccount.code, BigDecimal("800.00")))
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.NEEDS_ITEMIZATION
    }

    @Test
    fun `given a row targeting the Fixed Assets account, when committed, then it's redirected to Suspense too`() {
        val fixture = Fixture()

        val result = fixture.useCase.commit(
            fixture.request(ImportGlBalancesUseCase.Row(1, fixture.fixedAssetAccount.code, BigDecimal("18000.00")))
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.NEEDS_ITEMIZATION
    }

    @Test
    fun `given no contra_account_code, when committed, then it defaults to Opening Balance Equity`() {
        val fixture = Fixture()

        fixture.useCase.commit(
            fixture.request(ImportGlBalancesUseCase.Row(1, fixture.cashAccount.code, BigDecimal("100.00")))
        )

        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.lines.single { it.accountId == fixture.openingBalanceEquity.id }
    }

    @Test
    fun `given an explicit contra_account_code, when committed, then it uses that account instead of the default`() {
        val fixture = Fixture()

        fixture.useCase.commit(
            fixture.request(ImportGlBalancesUseCase.Row(1, fixture.cashAccount.code, BigDecimal("100.00"), contraAccountCode = fixture.suspenseAccount.code))
        )

        val posted = fixture.journalEntryRepository.findAllByCompany(fixture.company.id).single()
        posted.lines.single { it.accountId == fixture.suspenseAccount.id }
    }

    @Test
    fun `given an unknown account_code, when validated, then that row is rejected`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            fixture.request(ImportGlBalancesUseCase.Row(1, "9999", BigDecimal("100.00")))
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        val rowResult = success.rowResults.single()
        rowResult.status shouldBe OpeningImportRowStatus.REJECTED
        rowResult.errors.single() shouldBe "account_code '9999' not found"
    }

    @Test
    fun `given a zero amount, when validated, then that row is rejected`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            fixture.request(ImportGlBalancesUseCase.Row(1, fixture.cashAccount.code, BigDecimal.ZERO))
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        success.rowResults.single().status shouldBe OpeningImportRowStatus.REJECTED
    }

    @Test
    fun `given two rows targeting the same account_code, when validated, then the second is rejected as a duplicate`() {
        val fixture = Fixture()

        val result = fixture.useCase.validate(
            fixture.request(
                ImportGlBalancesUseCase.Row(1, fixture.cashAccount.code, BigDecimal("100.00")),
                ImportGlBalancesUseCase.Row(2, fixture.cashAccount.code, BigDecimal("50.00"))
            )
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        success.rowResults[0].status shouldBe OpeningImportRowStatus.ACCEPTED
        success.rowResults[1].status shouldBe OpeningImportRowStatus.REJECTED
        success.rowResults[1].errors.single() shouldBe "duplicate account_code '${fixture.cashAccount.code}' within this batch"
    }

    @Test
    fun `given a mix of accepted, rejected, and needs-itemization rows, when committed, then the batch tallies each count correctly`() {
        val fixture = Fixture()

        val result = fixture.useCase.commit(
            fixture.request(
                ImportGlBalancesUseCase.Row(1, fixture.cashAccount.code, BigDecimal("100.00")),
                ImportGlBalancesUseCase.Row(2, fixture.arAccount.code, BigDecimal("200.00")),
                ImportGlBalancesUseCase.Row(3, "9999", BigDecimal("300.00"))
            )
        )

        val success = result.shouldBeInstanceOf<ImportGlBalancesUseCase.Result.Success>()
        success.batch.rowCount shouldBe 3
        success.batch.acceptedCount shouldBe 1
        success.batch.needsItemizationCount shouldBe 1
        success.batch.rejectedCount shouldBe 1
    }
}
