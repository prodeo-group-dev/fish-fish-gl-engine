package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.EntryProvenance
import com.theprodeogroup.fish.domain.ledger.JournalEntry
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
import java.time.LocalDate
import java.util.Currency

/**
 * Who, why and what document, stored once with an entry (V36, GL-T-38 and GL-T-31; standards S4 and S6) against a real Postgres: the
 * repository stamps the request's audit context on INSERT only, an update never changes it, and outside a request nothing is recorded.
 * Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class JournalEntryProvenanceIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val gbp = Currency.getInstance("GBP")

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
        val company = Company.create(TenantId.generate(), "Provenance Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)).also { it.open(); periodRepository.save(it) }
        val cash = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accountRepository.save(it) }
        val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accountRepository.save(it) }
        return World(period, cash, sales)
    }

    private fun receipt(w: World, description: String) = JournalEntry.create(
        w.period.id, LocalDate.now(),
        listOf(
            JournalLine(w.cash.id, Money(BigDecimal("10.00"), gbp), TransactionSide.DEBIT),
            JournalLine(w.sales.id, Money(BigDecimal("10.00"), gbp), TransactionSide.CREDIT)
        ),
        JournalSource.MANUAL, description
    ).also { it.post() }

    private fun context(by: String, acting: String? = null, reason: String? = null) = AuditContext().also {
        it.recordedBy = by
        it.actingUser = acting
        it.reason = reason
        it.sourceReference = SourceReference(SourceReferenceType.SALES_INVOICE, "inv-1", "INV-0001")
        it.vatBasis = VatBasis("13.5", 41, LocalDate.of(2026, 7, 1))
    }

    @Test
    fun `given an entry stored inside a request, then who, why and the document are stored with it and read back`() {
        val w = world()
        val entry = receipt(w, "In a request")

        AuditContexts.runWith(context("SERVICE:sop", acting = "11111111-1111-4111-8111-111111111111", reason = "Customer sale")) { journalEntryRepository.save(entry) }

        journalEntryRepository.findById(entry.id)!!.provenance shouldBe EntryProvenance(
            "SERVICE:sop", "11111111-1111-4111-8111-111111111111", "Customer sale",
            SourceReference(SourceReferenceType.SALES_INVOICE, "inv-1", "INV-0001"), VatBasis("13.5", 41, LocalDate.of(2026, 7, 1))
        )
    }

    @Test
    fun `given an entry stored outside any request, then nothing is recorded, never a guess`() {
        val w = world()
        val entry = receipt(w, "Outside")

        journalEntryRepository.save(entry)

        journalEntryRepository.findById(entry.id)!!.provenance shouldBe null
    }

    @Test
    fun `given insertIfAbsent inside a request, then it stamps, and a second call with another actor changes nothing`() {
        val w = world()
        val entry = receipt(w, "Once")

        AuditContexts.runWith(context("USER:aaaa")) { journalEntryRepository.insertIfAbsent(entry) shouldBe true }
        AuditContexts.runWith(context("USER:bbbb")) { journalEntryRepository.insertIfAbsent(entry) shouldBe false }

        journalEntryRepository.findById(entry.id)!!.provenance!!.recordedBy shouldBe "USER:aaaa"
    }

    @Test
    fun `given an entry that is later reversed, then the reversal carries its own actor and reason and the original keeps its own`() {
        val w = world()
        val original = receipt(w, "To undo")
        AuditContexts.runWith(context("USER:original-poster")) { journalEntryRepository.save(original) }
        val reversal = original.reverse(java.time.Instant.now(), com.theprodeogroup.fish.domain.ledger.JournalEntryId.generate(), "Undo of To undo")!!

        AuditContexts.runWith(context("USER:undoer", reason = "Posted to the wrong customer")) { journalEntryRepository.recordReversal(original, reversal) shouldBe true }

        journalEntryRepository.findById(reversal.id)!!.provenance!!.let {
            it.recordedBy shouldBe "USER:undoer"
            it.reason shouldBe "Posted to the wrong customer"
        }
        val keptOriginal = journalEntryRepository.findById(original.id)!!
        keptOriginal.provenance!!.recordedBy shouldBe "USER:original-poster"
        keptOriginal.provenance!!.reason shouldBe null
    }

    @Test
    fun `given an existing entry saved again under a different actor, then the stored record does not change`() {
        val w = world()
        val entry = receipt(w, "Saved twice")
        AuditContexts.runWith(context("USER:first", reason = "first reason")) { journalEntryRepository.save(entry) }

        AuditContexts.runWith(context("USER:second", reason = "second reason")) { journalEntryRepository.save(entry) }

        journalEntryRepository.findById(entry.id)!!.provenance!!.let {
            it.recordedBy shouldBe "USER:first"
            it.reason shouldBe "first reason"
        }
    }
}
