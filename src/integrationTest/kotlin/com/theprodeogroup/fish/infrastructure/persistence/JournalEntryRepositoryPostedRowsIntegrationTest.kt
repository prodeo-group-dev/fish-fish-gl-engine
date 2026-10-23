package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val ON = LocalDate.of(2026, 10, 24)

/**
 * GL-FR-IMM-1e / GL-T-IMM-1 (docs/GL_Posted_Entry_Immutability_Requirements.md). A posted entry is never changed or deleted (Femi, 2026-10-23, Decisions_Log:
 * "Once a transaction is posted ... it is NEVER deleted"; "Agreed to all 3"). Traceability to the adopted standard "Corrections and immutability of posted entries" (2026-10-22): the tests on a changed
 * stored entry (different lines, different description, REVERSED saved back as POSTED) and on rows not rewritten trace to **S1** (never
 * edited or deleted); the reversal tests trace to **S2** (a correction is a new journal, the original only marked reversed) and **S3**
 * (the reversal's structured link to the original, `reversalOfEntryId`). On a real Postgres: saving an entry that is already stored changes ONLY its status (POSTED to REVERSED) and never touches its lines.
 * Before this, `save()` deleted every line and inserted them all again each time, even for a status change.
 */
class JournalEntryRepositoryPostedRowsIntegrationTest {

    private val periods = ExposedPeriodRepository()
    private val accounts = ExposedAccountRepository()
    private val entries = ExposedJournalEntryRepository()

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

    private class World(val periodId: com.theprodeogroup.fish.domain.ledger.PeriodId, val cash: Account, val revenue: Account)

    private fun world(): World {
        val companyId = CompanyId.generate()
        val period = Period.create(companyId, PeriodType.MONTH, ON, ON.plusDays(30)).also { it.open(); periods.save(it) }
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash").also { accounts.save(it) }
        val revenue = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }
        return World(period.id, cash, revenue)
    }

    private fun postedEntry(w: World, amount: String = "100.00", description: String = "Takings"): JournalEntry =
        JournalEntry.create(
            w.periodId, ON,
            listOf(
                JournalLine(w.cash.id, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                JournalLine(w.revenue.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, description
        ).also { it.post(); entries.save(it) }

    /** The physical row versions of the entry's lines (xmin): they change if and only if a line row was rewritten. */
    private fun lineRowVersions(entry: JournalEntry): List<String> = transaction {
        val result = mutableListOf<String>()
        exec("SELECT xmin::text FROM journal_lines WHERE journal_entry_id = '${entry.id.value}' ORDER BY line_index") { rs ->
            while (rs.next()) result += rs.getString(1)
        }
        result
    }

    private fun storedLines(entry: JournalEntry): List<String> = transaction {
        val result = mutableListOf<String>()
        exec("SELECT line_index || ':' || account_id || ':' || amount || ':' || side FROM journal_lines WHERE journal_entry_id = '${entry.id.value}' ORDER BY line_index") { rs ->
            while (rs.next()) result += rs.getString(1)
        }
        result
    }

    @Test
    fun `given a stored posted entry, when it is marked reversed and saved, then only its status changes and its line rows are not rewritten`() {
        val w = world()
        val original = postedEntry(w)
        val versionsBefore = lineRowVersions(original)
        val linesBefore = storedLines(original)
        versionsBefore.size shouldBe 2

        original.reverse(Instant.now())!!.also { entries.save(original) }

        entries.findById(original.id)!!.status shouldBe PostingStatus.REVERSED
        lineRowVersions(original) shouldBe versionsBefore
        storedLines(original) shouldBe linesBefore
    }

    @Test
    fun `given a stored entry, when it is saved again unchanged, then nothing is written and nothing is refused`() {
        val w = world()
        val original = postedEntry(w)
        val versionsBefore = lineRowVersions(original)

        entries.save(original)
        entries.save(original)

        lineRowVersions(original) shouldBe versionsBefore
        entries.findById(original.id)!!.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given a stored posted entry, when a save carries different lines or a different description, then it is refused and the stored entry is untouched`() {
        val w = world()
        val original = postedEntry(w, "100.00", "Takings")
        val linesBefore = storedLines(original)

        val changedLines = JournalEntry.create(
            w.periodId, ON,
            listOf(
                JournalLine(w.cash.id, Money(BigDecimal("999.00"), GBP), TransactionSide.DEBIT),
                JournalLine(w.revenue.id, Money(BigDecimal("999.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, "Takings", original.id
        ).also { it.post() }
        val changedDescription = JournalEntry.create(
            w.periodId, ON,
            listOf(
                JournalLine(w.cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(w.revenue.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, "Something else", original.id
        ).also { it.post() }

        shouldThrow<IllegalStateException> { entries.save(changedLines) }
        shouldThrow<IllegalStateException> { entries.save(changedDescription) }

        storedLines(original) shouldBe linesBefore
        entries.findById(original.id)!!.description shouldBe "Takings"
    }

    @Test
    fun `given a stored reversed entry, when a save claims it is posted again, then it is refused`() {
        val w = world()
        val original = postedEntry(w)
        original.reverse(Instant.now())!!.also { entries.save(original) }
        val claimsPosted = JournalEntry.create(
            w.periodId, ON,
            listOf(
                JournalLine(w.cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(w.revenue.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, "Takings", original.id
        ).also { it.post() }

        shouldThrow<IllegalStateException> { entries.save(claimsPosted) }

        entries.findById(original.id)!!.status shouldBe PostingStatus.REVERSED
    }

    @Test
    fun `given a reversal recorded on a real database, then the reversal is stored, the original is marked reversed, and the original's lines are untouched`() {
        val w = world()
        val original = postedEntry(w)
        val versionsBefore = lineRowVersions(original)
        val reversal = original.reverse(Instant.now())!!

        entries.recordReversal(original, reversal) shouldBe true

        entries.findById(original.id)!!.status shouldBe PostingStatus.REVERSED
        entries.findById(reversal.id)!!.reversalOfEntryId shouldBe original.id
        lineRowVersions(original) shouldBe versionsBefore
    }
}
