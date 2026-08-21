package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 19)

/**
 * Verifies the core Ledger repositories genuinely round-trip through a
 * real Postgres database (docs/DDD_Design.md Section 10.1) - saving an
 * aggregate and reloading it by ID reconstructs an object identical to
 * what was saved, including nested `JournalLine`s and their dimension
 * tags. Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't
 * set, matching `DatabaseMigratorIntegrationTest`'s precedent.
 */
class CoreLedgerRepositoriesIntegrationTest {

    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping (see docs/DDD_Design.md Section 10 for local setup)"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    @Test
    fun `given a child Account with a parent and an expense classification, when saved and reloaded, then the relationship and classification round-trip`() {
        val companyId = CompanyId.generate()
        val parent = Account.create(companyId, AccountType.EXPENSE, null, "5000", "Overheads")
        accountRepository.save(parent)
        val child = Account.create(
            companyId, AccountType.EXPENSE, null, "5100", "Factory Rent",
            expenseClassification = ExpenseClassification.FACTORY_OVERHEAD, parentId = parent.id
        )
        child.recordActivity()

        accountRepository.save(child)
        val reloaded = requireNotNull(accountRepository.findById(child.id))

        reloaded.parentId shouldBe parent.id
        reloaded.expenseClassification shouldBe ExpenseClassification.FACTORY_OVERHEAD
        reloaded.validateDeletion().isValid shouldBe false // hasPostedActivity round-tripped as true
    }

    @Test
    fun `given an Account with every field populated, when saved and reloaded, then it matches exactly`() {
        val companyId = CompanyId.generate()
        val parent = Account.create(companyId, AccountType.EXPENSE, null, "5000", "Overheads")
        accountRepository.save(parent)
        val child = Account.create(
            companyId, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Work in Progress",
            expenseClassification = null, parentId = null
        )
        child.recordActivity()
        child.deactivate()

        accountRepository.save(child)
        val reloaded = requireNotNull(accountRepository.findById(child.id))

        reloaded.id shouldBe child.id
        reloaded.companyId shouldBe child.companyId
        reloaded.type shouldBe AccountType.ASSET
        reloaded.classification shouldBe AccountClassification.CURRENT
        reloaded.expenseClassification shouldBe null
        reloaded.code shouldBe "1300"
        reloaded.name shouldBe "Work in Progress"
        reloaded.parentId shouldBe null
        reloaded.active shouldBe false
    }

    @Test
    fun `given an Account is saved twice with a changed field, when reloaded, then it updates rather than duplicating`() {
        val companyId = CompanyId.generate()
        val account = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        accountRepository.save(account)
        account.deactivate()
        accountRepository.save(account)

        val all = accountRepository.findAllByCompany(companyId)

        all.count { it.id == account.id } shouldBe 1
        requireNotNull(all.find { it.id == account.id }).active shouldBe false
    }

    @Test
    fun `given a Period is saved, when reloaded by id, then every field round-trips correctly`() {
        val companyId = CompanyId.generate()
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()

        periodRepository.save(period)
        val reloaded = requireNotNull(periodRepository.findById(period.id))

        reloaded.id shouldBe period.id
        reloaded.companyId shouldBe companyId
        reloaded.periodType shouldBe PeriodType.MONTH
        reloaded.startDate shouldBe TODAY
        reloaded.endDate shouldBe TODAY.plusDays(30)
        reloaded.status shouldBe period.status
    }

    @Test
    fun `given a JournalEntry with multiple lines and dimensions, when saved and reloaded, then every line round-trips correctly`() {
        val companyId = CompanyId.generate()
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
        val revenue = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        accountRepository.save(cash)
        accountRepository.save(revenue)

        val entry = JournalEntry.create(
            period.id, TODAY,
            listOf(
                JournalLine(cash.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
                JournalLine(
                    revenue.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT,
                    mapOf(DimensionType.CUSTOMER to "a-customer-id")
                )
            ),
            JournalSource.MANUAL, "Integration test sale"
        )
        entry.post()

        journalEntryRepository.save(entry)
        val reloaded = requireNotNull(journalEntryRepository.findById(entry.id))

        reloaded.id shouldBe entry.id
        reloaded.periodId shouldBe period.id
        reloaded.date shouldBe TODAY
        reloaded.source shouldBe JournalSource.MANUAL
        reloaded.description shouldBe "Integration test sale"
        reloaded.status shouldBe entry.status
        reloaded.lines shouldContainExactly entry.lines
    }

    @Test
    fun `given JournalEntries across two Periods for one Company, when found by company, then both are returned`() {
        val companyId = CompanyId.generate()
        val period1 = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        val period2 = Period.create(companyId, PeriodType.MONTH, TODAY.plusDays(31), TODAY.plusDays(60))
        periodRepository.save(period1)
        periodRepository.save(period2)
        val expense = Account.create(companyId, AccountType.EXPENSE, null, "5000", "Rent")
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
        accountRepository.save(expense)
        accountRepository.save(cash)

        val entry1 = JournalEntry.create(
            period1.id, TODAY,
            listOf(
                JournalLine(expense.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        val entry2 = JournalEntry.create(
            period2.id, TODAY.plusDays(31),
            listOf(
                JournalLine(expense.id, Money(BigDecimal("200.00"), GBP), TransactionSide.DEBIT),
                JournalLine(cash.id, Money(BigDecimal("200.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        journalEntryRepository.save(entry1)
        journalEntryRepository.save(entry2)

        val found = journalEntryRepository.findAllByCompany(companyId)

        found.map { it.id }.toSet() shouldBe setOf(entry1.id, entry2.id)
    }
}
