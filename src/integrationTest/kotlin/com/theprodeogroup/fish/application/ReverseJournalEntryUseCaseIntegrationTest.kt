package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
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
 * `ReverseJournalEntryUseCaseTest` (`src/test`) uses in-memory fakes -
 * exercises the same use case against real Exposed repositories and a
 * live Postgres database (docs/DDD_Design.md Section 10.12). Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class ReverseJournalEntryUseCaseIntegrationTest {

    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val useCase = ReverseJournalEntryUseCase(journalEntryRepository, periodRepository, accountRepository)

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
    fun `given a Posted entry against a real database, when reversed, then both entries round-trip correctly`() {
        val companyId = CompanyId.generate()
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
        val revenue = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        accountRepository.save(cash)
        accountRepository.save(revenue)

        val original = JournalEntry.create(
            period.id, TODAY,
            listOf(
                JournalLine(cash.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        original.post()
        journalEntryRepository.save(original)

        val result = useCase.execute(original.id)

        val success = result.shouldBeInstanceOf<ReverseJournalEntryResult.Success>()

        val reloadedOriginal = requireNotNull(journalEntryRepository.findById(original.id))
        reloadedOriginal.status shouldBe PostingStatus.REVERSED

        val reloadedReversal = requireNotNull(journalEntryRepository.findById(success.reversalEntry.id))
        reloadedReversal.status shouldBe PostingStatus.POSTED
        reloadedReversal.reversalOfEntryId shouldBe original.id
        reloadedReversal.lines.map { it.accountId to it.side } shouldBe
            original.lines.map { it.accountId to it.side.opposite() }
    }
}
