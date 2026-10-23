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
import io.kotest.matchers.string.shouldContain
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
 * GL-FR-IMM-1a to 1d / GL-T-IMM-2 (docs/GL_Posted_Entry_Immutability_Requirements.md). The database itself refuses to change or delete posted
 * journal rows (V37). Traces to the adopted standard "Corrections and immutability of posted entries" (2026-10-22): **S1** (a posted entry is never edited or deleted: no change to its lines, amounts, date, accounts or
 * description; and the V36 audit columns are written once, at insert), **S2** (a correction is a new journal: the one permitted change to a
 * stored entry is POSTED to REVERSED), **S3** (the reversal link is written at insert and never changed). Femi agreed the enforcement 2026-10-23 (Decisions_Log: "Agreed to all 3").
 * S5 (the reconciliation lock) is Cash and Bank Books', not enforced here (Femi, 2026-10-23).
 *
 * Real Postgres only: triggers cannot be proved with fakes. Test cleanup NEVER deletes or truncates these tables (each test makes its own
 * Company, so rows never collide).
 */
class PostedJournalRowsAreImmutableIntegrationTest {

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

    private fun postedEntry(w: World): JournalEntry =
        JournalEntry.create(
            w.periodId, ON,
            listOf(
                JournalLine(w.cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(w.revenue.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, "Takings"
        ).also { it.post(); entries.save(it) }

    /** Runs [sql] in its own transaction and returns the error message if the database refused it, else null. */
    private fun refusal(sql: String): String? = try {
        transaction { exec(sql) }
        null
    } catch (e: Exception) {
        generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    private fun status(entry: JournalEntry): String = transaction {
        var s = ""
        exec("SELECT status FROM journal_entries WHERE id = '${entry.id.value}'") { rs -> if (rs.next()) s = rs.getString(1) }
        s
    }

    private fun count(table: String, entryId: java.util.UUID): Int = transaction {
        var n = 0
        val column = if (table == "journal_entries") "id" else "journal_entry_id"
        exec("SELECT count(*) FROM $table WHERE $column = '$entryId'") { rs -> if (rs.next()) n = rs.getInt(1) }
        n
    }

    @Test
    fun `S1 - deleting a posted entry or one of its lines is refused, and both survive`() {
        val e = postedEntry(world())

        (refusal("DELETE FROM journal_lines WHERE journal_entry_id = '${e.id.value}' AND line_index = 0") ?: "") shouldContain "never deleted"
        (refusal("DELETE FROM journal_lines WHERE journal_entry_id = '${e.id.value}'") ?: "") shouldContain "never deleted"
        (refusal("DELETE FROM journal_entries WHERE id = '${e.id.value}'") ?: "") shouldContain "never deleted"

        count("journal_entries", e.id.value) shouldBe 1
        count("journal_lines", e.id.value) shouldBe 2
    }

    @Test
    fun `S1 - truncating the journal tables is refused, and the rows survive`() {
        val e = postedEntry(world())

        (refusal("TRUNCATE journal_lines") ?: "") shouldContain "never truncated"
        (refusal("TRUNCATE journal_entries CASCADE") ?: "") shouldContain "never truncated"
        // Without CASCADE, Postgres refuses first because other tables reference journal_entries: refused either way, by a different guard.
        (refusal("TRUNCATE journal_lines, journal_entries") != null) shouldBe true

        count("journal_entries", e.id.value) shouldBe 1
        count("journal_lines", e.id.value) shouldBe 2
    }

    @Test
    fun `S1 - changing any column of a line is refused, and an update that changes nothing is allowed`() {
        val e = postedEntry(world())
        val where = "journal_entry_id = '${e.id.value}' AND line_index = 0"

        for (set in listOf("amount = 999.00", "side = 'CREDIT'", "currency = 'EUR'", "dimensions = '{\"CUSTOMER\":\"x\"}'", "line_index = 7", "account_id = '${java.util.UUID.randomUUID()}'")) {
            (refusal("UPDATE journal_lines SET $set WHERE $where") ?: "") shouldContain "never changed"
        }
        refusal("UPDATE journal_lines SET amount = amount WHERE $where") shouldBe null
    }

    @Test
    fun `S1 - changing any protected column of an entry, including the audit columns written once at insert, is refused`() {
        val w = world()
        val e = postedEntry(w)
        val id = "id = '${e.id.value}'"

        val protectedChanges = listOf(
            "description = 'edited'", "entry_date = DATE '2020-01-01'", "source = 'IMPORT'",
            "period_id = '${java.util.UUID.randomUUID()}'", "reversal_of_entry_id = '${e.id.value}'",
            "recorded_by = 'USER:someone'", "acting_user = 'someone'", "reason = 'because'",
            "source_ref_type = 'SALES_INVOICE'", "source_ref_id = 'x'", "source_ref_number = 'INV-1'",
            "vat_basis_rate_percent = '20'", "vat_basis_rulebook_version = 1", "vat_basis_effective_from = DATE '2020-01-01'",
            "recorded_at = now() + interval '1 day'"
        )
        for (set in protectedChanges) {
            (refusal("UPDATE journal_entries SET $set WHERE $id") ?: "") shouldContain "only its status"
        }
        refusal("UPDATE journal_entries SET description = description WHERE $id") shouldBe null
        status(e) shouldBe "POSTED"
    }

    @Test
    fun `S2 - the one permitted change to a stored entry is POSTED to REVERSED, and it cannot go back or sideways`() {
        val e = postedEntry(world())
        val id = "id = '${e.id.value}'"

        (refusal("UPDATE journal_entries SET status = 'DRAFT' WHERE $id") ?: "") shouldContain "POSTED to REVERSED"
        (refusal("UPDATE journal_entries SET status = 'PENDING' WHERE $id") ?: "") shouldContain "POSTED to REVERSED"
        status(e) shouldBe "POSTED"

        refusal("UPDATE journal_entries SET status = 'REVERSED' WHERE $id") shouldBe null
        status(e) shouldBe "REVERSED"

        (refusal("UPDATE journal_entries SET status = 'POSTED' WHERE $id") ?: "") shouldContain "POSTED to REVERSED"
        (refusal("UPDATE journal_entries SET status = 'DRAFT' WHERE $id") ?: "") shouldContain "POSTED to REVERSED"
        status(e) shouldBe "REVERSED"
    }

    @Test
    fun `S1 - a status change that also changes another column is refused, even POSTED to REVERSED`() {
        val e = postedEntry(world())

        (refusal("UPDATE journal_entries SET status = 'REVERSED', description = 'edited' WHERE id = '${e.id.value}'") ?: "") shouldContain "only its status"

        status(e) shouldBe "POSTED"
    }

    @Test
    fun `S2 and S3 - a reversal is stored through the repository under the triggers, and the original is marked reversed with its lines untouched`() {
        val e = postedEntry(world())
        val reversal = e.reverse(Instant.now())!!

        entries.recordReversal(e, reversal) shouldBe true

        entries.findById(e.id)!!.status shouldBe PostingStatus.REVERSED
        entries.findById(reversal.id)!!.reversalOfEntryId shouldBe e.id
        count("journal_lines", e.id.value) shouldBe 2
    }
}
