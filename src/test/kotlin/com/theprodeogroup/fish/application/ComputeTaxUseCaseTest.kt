package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tax.TaxRule
import com.theprodeogroup.fish.domain.tax.TaxType
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 20)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`TenancyRepositoryFakes.kt`/`TaxRepositoryFakes.kt`. */
class ComputeTaxUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val taxComputationRepository = FakeTaxComputationRepository()
    private val useCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)

    private val companyId = CompanyId.generate()
    private val taxRule = TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))

    private fun period(): Period {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        return period
    }

    private fun account(type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        val account = Account.create(companyId, type, classification, "4000", "Test Account")
        accountRepository.save(account)
        return account
    }

    @Test
    fun `given a profitable Period, when executed, then it returns the computed tax due`() {
        val period = period()
        val revenue = account(AccountType.REVENUE)
        val expense = account(AccountType.EXPENSE)
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            )
        )
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(expense.id, Money(BigDecimal("400.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("400.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val result = useCase.execute(ComputeTaxUseCase.Request(companyId, period.id, taxRule, GBP))

        val success = result.shouldBeInstanceOf<ComputeTaxResult.Success>()
        success.computation.taxableProfit shouldBe Money(BigDecimal("600.00"), GBP)
        success.computation.taxDue shouldBe Money(BigDecimal("180.00"), GBP)
    }

    @Test
    fun `given a successful computation, then it is persisted via TaxComputationRepository`() {
        val period = period()
        account(AccountType.REVENUE)

        val result = useCase.execute(ComputeTaxUseCase.Request(companyId, period.id, taxRule, GBP))

        val success = result.shouldBeInstanceOf<ComputeTaxResult.Success>()
        taxComputationRepository.saveCalls shouldContain success.computation.id
        taxComputationRepository.findById(success.computation.id)?.taxRuleId shouldBe taxRule.id
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val result = useCase.execute(ComputeTaxUseCase.Request(companyId, PeriodId.generate(), taxRule, GBP))

        result.shouldBeInstanceOf<ComputeTaxResult.PeriodNotFound>()
    }

    @Test
    fun `given a Period belonging to a different Company, when executed, then it returns PeriodBelongsToDifferentCompany`() {
        val period = period()
        val otherCompanyId = CompanyId.generate()

        val result = useCase.execute(ComputeTaxUseCase.Request(otherCompanyId, period.id, taxRule, GBP))

        result.shouldBeInstanceOf<ComputeTaxResult.PeriodBelongsToDifferentCompany>()
    }

    @Test
    fun `given a Company with no Accounts at all, when executed, then it returns NoAccountsForCompany`() {
        val period = period()

        val result = useCase.execute(ComputeTaxUseCase.Request(companyId, period.id, taxRule, GBP))

        result.shouldBeInstanceOf<ComputeTaxResult.NoAccountsForCompany>()
    }

    private fun postedEntry(periodId: PeriodId, vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(periodId, TODAY, lines.toList(), JournalSource.MANUAL)
        entry.post()
        return entry
    }
}
