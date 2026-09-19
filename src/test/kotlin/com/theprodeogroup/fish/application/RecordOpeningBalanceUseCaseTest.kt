package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.Period
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
private val TODAY: LocalDate = LocalDate.of(2026, 9, 3)

/**
 * `RecordOpeningBalanceUseCase` (2026-09-03, "the opening figures for
 * the first fiscal year should be available throughout the year") -
 * the standing, always-available counterpart to `OnboardTenantUseCase`'s
 * one-shot `openingCashBalance` field.
 */
class RecordOpeningBalanceUseCaseTest {

    private class Fixture(configureContraAccount: Boolean = true, openPeriod: Boolean = true) {
        val companyRepository = FakeCompanyRepository()
        val periodRepository = FakePeriodRepository()
        val accountRepository = FakeAccountRepository()
        val journalEntryRepository = FakeJournalEntryRepository()
        val useCase = RecordOpeningBalanceUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)

        val company = Company.create(TenantId.generate(), "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP).also { companyRepository.save(it) }

        val fixedAssetAccount = Account.create(company.id, AccountType.ASSET, AccountClassification.NON_CURRENT, "1250", "Company Vehicles")
            .also { accountRepository.save(it) }
        val loanAccount = Account.create(company.id, AccountType.LIABILITY, AccountClassification.NON_CURRENT, "2200", "Bank Loan")
            .also { accountRepository.save(it) }

        val openingBalanceEquityAccount = if (configureContraAccount) {
            Account.create(company.id, AccountType.EQUITY, null, ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE, "Opening Balance Equity")
                .also { accountRepository.save(it) }
        } else null

        val suspenseAccount = if (configureContraAccount) {
            Account.create(company.id, AccountType.EQUITY, null, ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE, "Suspense Account")
                .also { accountRepository.save(it) }
        } else null

        val period = if (openPeriod) {
            Period.create(company.id, PeriodType.MONTH, TODAY.minusDays(10), TODAY.plusDays(20)).also {
                it.open()
                periodRepository.save(it)
            }
        } else null
    }

    @Test
    fun `given an Asset account, when its opening balance is recorded, then the account is debited and the contra account is credited`() {
        val fixture = Fixture()

        val result = fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, fixture.fixedAssetAccount.id, fixture.openingBalanceEquityAccount!!.id, BigDecimal("18000.00"), TODAY)
        )

        val success = result.shouldBeInstanceOf<RecordOpeningBalanceUseCase.Result.Success>()
        val assetLine = success.journalEntry.lines.single { it.accountId == fixture.fixedAssetAccount.id }
        val equityLine = success.journalEntry.lines.single { it.accountId == fixture.openingBalanceEquityAccount!!.id }
        assetLine.side shouldBe TransactionSide.DEBIT
        equityLine.side shouldBe TransactionSide.CREDIT
    }

    @Test
    fun `given a Liability account, when its opening balance is recorded, then the account is credited and the contra account is debited - normal balance flips the sides`() {
        val fixture = Fixture()

        val result = fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, fixture.loanAccount.id, fixture.openingBalanceEquityAccount!!.id, BigDecimal("5000.00"), TODAY)
        )

        val success = result.shouldBeInstanceOf<RecordOpeningBalanceUseCase.Result.Success>()
        val liabilityLine = success.journalEntry.lines.single { it.accountId == fixture.loanAccount.id }
        val equityLine = success.journalEntry.lines.single { it.accountId == fixture.openingBalanceEquityAccount!!.id }
        liabilityLine.side shouldBe TransactionSide.CREDIT
        equityLine.side shouldBe TransactionSide.DEBIT
    }

    @Test
    fun `given a different contra account (Suspense, not Opening Balance Equity), when executed, then it posts against that account instead - proving the contra account is no longer hardcoded`() {
        val fixture = Fixture()

        val result = fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, fixture.fixedAssetAccount.id, fixture.suspenseAccount!!.id, BigDecimal("5000.00"), TODAY)
        )

        val success = result.shouldBeInstanceOf<RecordOpeningBalanceUseCase.Result.Success>()
        val assetLine = success.journalEntry.lines.single { it.accountId == fixture.fixedAssetAccount.id }
        val suspenseLine = success.journalEntry.lines.single { it.accountId == fixture.suspenseAccount!!.id }
        assetLine.side shouldBe TransactionSide.DEBIT
        suspenseLine.side shouldBe TransactionSide.CREDIT
    }

    @Test
    fun `given a nonexistent Account, when executed, then it reports not found`() {
        val fixture = Fixture()

        val result = fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, com.theprodeogroup.fish.domain.ledger.AccountId.generate(), fixture.openingBalanceEquityAccount!!.id, BigDecimal("100.00"), TODAY)
        )

        result shouldBe RecordOpeningBalanceUseCase.Result.AccountNotFound
    }

    @Test
    fun `given no open Period covering the date, when executed, then it fails - available any time there IS an open Period, not restricted further`() {
        val fixture = Fixture(openPeriod = false)

        val result = fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, fixture.fixedAssetAccount.id, fixture.openingBalanceEquityAccount!!.id, BigDecimal("100.00"), TODAY)
        )

        result shouldBe RecordOpeningBalanceUseCase.Result.NoOpenPeriod
    }

    @Test
    fun `given no contra account configured, when executed, then it fails`() {
        val fixture = Fixture(configureContraAccount = false)

        val result = fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, fixture.fixedAssetAccount.id, com.theprodeogroup.fish.domain.ledger.AccountId.generate(), BigDecimal("100.00"), TODAY)
        )

        result shouldBe RecordOpeningBalanceUseCase.Result.ContraAccountNotFound
    }

    @Test
    fun `given a zero amount, when executed, then it fails`() {
        val fixture = Fixture()

        val result = fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, fixture.fixedAssetAccount.id, fixture.openingBalanceEquityAccount!!.id, BigDecimal.ZERO, TODAY)
        )

        result.shouldBeInstanceOf<RecordOpeningBalanceUseCase.Result.InvalidAmount>()
    }

    @Test
    fun `given a later delivery entered mid-year for the same account, when executed again, then it posts a second, independent opening-balance entry`() {
        val fixture = Fixture()
        fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, fixture.fixedAssetAccount.id, fixture.openingBalanceEquityAccount!!.id, BigDecimal("10000.00"), TODAY)
        )

        val result = fixture.useCase.execute(
            RecordOpeningBalanceUseCase.Request(fixture.company.id, fixture.fixedAssetAccount.id, fixture.openingBalanceEquityAccount!!.id, BigDecimal("8000.00"), TODAY.plusDays(5))
        )

        result.shouldBeInstanceOf<RecordOpeningBalanceUseCase.Result.Success>()
        fixture.journalEntryRepository.findAllByCompany(fixture.company.id).size shouldBe 2
    }
}
