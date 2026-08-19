package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 20)

/**
 * `PostJournalEntryUseCaseTest` (`src/test`) uses in-memory fakes -
 * exercises the same use case against real Exposed repositories and a
 * live Postgres database (docs/DDD_Design.md Section 10.8), the first
 * time this codebase has verified `Account`/`Period`/`JournalEntry` being
 * orchestrated together through the application layer, not just each
 * persisted independently. Skips (not fails) if `FISH_DB_USER`/
 * `FISH_DB_PASSWORD` aren't set.
 */
class PostJournalEntryUseCaseIntegrationTest {

    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val useCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)

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
    fun `given a valid request against a real database, when executed, then the JournalEntry and both Accounts' activity flags round-trip correctly`() {
        val companyId = CompanyId.generate()
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
        val revenue = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        accountRepository.save(cash)
        accountRepository.save(revenue)

        val lines = listOf(
            JournalLine(cash.id, Money(BigDecimal("250.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("250.00"), GBP), TransactionSide.CREDIT)
        )

        val result = useCase.execute(PostJournalEntryUseCase.Request(period.id, TODAY, lines, JournalSource.MANUAL, "Integration test sale"))

        val success = result.shouldBeInstanceOf<PostJournalEntryResult.Success>()
        val reloadedEntry = requireNotNull(journalEntryRepository.findById(success.entry.id))
        reloadedEntry.status shouldBe PostingStatus.POSTED
        reloadedEntry.periodId shouldBe period.id
        reloadedEntry.lines shouldBe success.entry.lines

        val reloadedCash = requireNotNull(accountRepository.findById(cash.id))
        val reloadedRevenue = requireNotNull(accountRepository.findById(revenue.id))
        reloadedCash.validateDeletion().isValid shouldBe false
        reloadedRevenue.validateDeletion().isValid shouldBe false
    }
}
