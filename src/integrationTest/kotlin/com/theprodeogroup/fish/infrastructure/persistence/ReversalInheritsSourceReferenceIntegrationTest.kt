package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.EntryProvenance
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.SourceReference
import com.theprodeogroup.fish.domain.ledger.SourceReferenceType
import com.theprodeogroup.fish.domain.ledger.VatBasis
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.infrastructure.audit.AuditContext
import com.theprodeogroup.fish.infrastructure.audit.AuditContexts
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

/**
 * A reversing entry names the same source document as the entry it reverses (standard S6: a correction links to the document it corrects),
 * so a cash book shows the original receipt number against the Undo row (CM, 2026-10-23; unblocks CBB_SEND_SOURCE_REFERENCE). The copy is in
 * `recordReversal` only, which both the Undo of a cash-book entry and the generic reversal call. Against a real Postgres. Skips (not fails)
 * if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class ReversalInheritsSourceReferenceIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val gbp = Currency.getInstance("GBP")
    private val receiptReference = SourceReference(SourceReferenceType.CASH_BOOK_ENTRY, "R-000123", "R-000123")

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    private class World(val period: Period, val cash: Account, val sales: Account)

    private fun world(): World {
        val company = Company.create(TenantId.generate(), "Reversal Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)).also { it.open(); periodRepository.save(it) }
        val cash = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accountRepository.save(it) }
        val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accountRepository.save(it) }
        return World(period, cash, sales)
    }

    private fun receipt(w: World) = JournalEntry.create(
        w.period.id, LocalDate.now(),
        listOf(
            JournalLine(w.cash.id, Money(BigDecimal("10.00"), gbp), TransactionSide.DEBIT),
            JournalLine(w.sales.id, Money(BigDecimal("10.00"), gbp), TransactionSide.CREDIT)
        ),
        JournalSource.CASH_BOOK, "Takings"
    ).also { it.post() }

    /** What a route sets for an Undo or a reversal: who and why, and no source document of its own. */
    private fun undoContext(by: String, reason: String? = null) = AuditContext().also { it.recordedBy = by; it.reason = reason }

    /** What the original was stored with: all five parts, so a leak of any of them into the reversal would show. */
    private fun fullContext(reference: SourceReference?) = AuditContext().also {
        it.recordedBy = "SERVICE:cbb"
        it.actingUser = "11111111-1111-4111-8111-111111111111"
        it.reason = "Customer paid"
        it.sourceReference = reference
        it.vatBasis = VatBasis("13.5", 41, LocalDate.of(2026, 7, 1))
    }

    private fun stored(w: World, reference: SourceReference?): JournalEntry {
        val original = receipt(w)
        AuditContexts.runWith(fullContext(reference)) { journalEntryRepository.save(original) }
        return journalEntryRepository.findById(original.id)!!
    }

    private fun reverse(original: JournalEntry) = original.reverse(Instant.now(), JournalEntryId.generate(), "Undo of ${original.description}")!!

    @Test
    fun `given an original with a source reference, when it is reversed, then the reversal names the same document, with its own actor and reason, and the original is only marked reversed`() {
        val w = world()
        val original = stored(w, receiptReference)
        val originalProvenance = original.provenance
        val reversal = reverse(original)

        AuditContexts.runWith(undoContext("USER:undoer", "Wrong customer")) { journalEntryRepository.recordReversal(original, reversal) shouldBe true }

        val storedReversal = journalEntryRepository.findById(reversal.id)!!
        storedReversal.provenance!!.sourceReference shouldBe receiptReference
        storedReversal.provenance!!.recordedBy shouldBe "USER:undoer"
        storedReversal.provenance!!.reason shouldBe "Wrong customer"
        val storedOriginal = journalEntryRepository.findById(original.id)!!
        storedOriginal.status shouldBe PostingStatus.REVERSED
        storedOriginal.provenance shouldBe originalProvenance
    }

    @Test
    fun `given an original with no source reference, when it is reversed, then the reversal has none either - nothing is guessed`() {
        val w = world()
        val original = stored(w, null)
        val reversal = reverse(original)

        AuditContexts.runWith(undoContext("USER:undoer")) { journalEntryRepository.recordReversal(original, reversal) shouldBe true }

        journalEntryRepository.findById(reversal.id)!!.provenance!!.sourceReference shouldBe null
    }

    @Test
    fun `given a request that names its own source reference, when an entry is reversed, then the request's reference wins`() {
        val w = world()
        val original = stored(w, receiptReference)
        val reversal = reverse(original)
        val own = SourceReference(SourceReferenceType.CORRECTION, "fix-9", "FIX-9")

        AuditContexts.runWith(undoContext("USER:undoer").also { it.sourceReference = own }) { journalEntryRepository.recordReversal(original, reversal) }

        journalEntryRepository.findById(reversal.id)!!.provenance!!.sourceReference shouldBe own
    }

    @Test
    fun `given a reversal, then only the document reference is inherited - not the original's actor, reason or VAT basis`() {
        val w = world()
        val original = stored(w, receiptReference)
        val reversal = reverse(original)

        AuditContexts.runWith(undoContext("USER:undoer")) { journalEntryRepository.recordReversal(original, reversal) }

        journalEntryRepository.findById(reversal.id)!!.provenance shouldBe EntryProvenance("USER:undoer", null, null, receiptReference, null)
    }

    @Test
    fun `given a reversal stored outside any request, then it still names the original's document - a fact of the entry, not a guess`() {
        val w = world()
        val original = stored(w, receiptReference)
        val reversal = reverse(original)

        journalEntryRepository.recordReversal(original, reversal) shouldBe true

        journalEntryRepository.findById(reversal.id)!!.provenance shouldBe EntryProvenance(null, null, null, receiptReference, null)
    }

    @Test
    fun `given the same reversal recorded twice, then the second does nothing and the stored reference stays the first`() {
        val w = world()
        val original = stored(w, receiptReference)
        val reversal = reverse(original)
        AuditContexts.runWith(undoContext("USER:first")) { journalEntryRepository.recordReversal(original, reversal) shouldBe true }
        val retryOriginal = journalEntryRepository.findById(original.id)!!

        AuditContexts.runWith(undoContext("USER:second").also { it.sourceReference = SourceReference(SourceReferenceType.CORRECTION, "x", null) }) {
            journalEntryRepository.recordReversal(retryOriginal, reversal) shouldBe false
        }

        journalEntryRepository.findById(reversal.id)!!.provenance!!.let {
            it.recordedBy shouldBe "USER:first"
            it.sourceReference shouldBe receiptReference
        }
    }
}
