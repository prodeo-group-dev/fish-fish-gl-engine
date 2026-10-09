package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationStatus
import com.theprodeogroup.fish.domain.ledger.BankStatementLine
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 10, 3)

/**
 * Verifies [ExposedBankReconciliationRepository] genuinely round-trips
 * through a real Postgres database, same discipline as
 * `AuditLogRepositoryIntegrationTest`. Skips (not fails) if
 * `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class BankReconciliationRepositoryIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val bankReconciliationRepository = ExposedBankReconciliationRepository()

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

    private fun newCompany(): com.theprodeogroup.fish.domain.tenancy.CompanyId {
        val tenant = TenantId.generate()
        val company = Company.create(tenant, "Bank Rec Test Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        return company.id
    }

    private fun newCashAccount(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId) =
        Account.create(companyId, AccountType.ASSET, com.theprodeogroup.fish.domain.ledger.AccountClassification.CURRENT, "1000", "Cash").also { accountRepository.save(it) }

    private fun postedEntry(companyId: com.theprodeogroup.fish.domain.tenancy.CompanyId, cashAccountId: com.theprodeogroup.fish.domain.ledger.AccountId, amount: String): JournalEntry {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY.minusDays(5), TODAY.plusDays(25)).also { it.open(); periodRepository.save(it) }
        val revenueAccount = Account.create(companyId, AccountType.REVENUE, null, "4000", "Revenue").also { accountRepository.save(it) }
        val entry = JournalEntry.create(
            period.id, TODAY,
            listOf(
                JournalLine(cashAccountId, Money(BigDecimal(amount), GBP), TransactionSide.DEBIT),
                JournalLine(revenueAccount.id, Money(BigDecimal(amount), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        journalEntryRepository.save(entry)
        return entry
    }

    @Test
    fun `given a reconciliation with one statement line, when saved and reloaded, then every field round-trips`() {
        val companyId = newCompany()
        val cashAccount = newCashAccount(companyId)
        val statementLine = BankStatementLine(date = TODAY, amount = Money(BigDecimal("500.00"), GBP), direction = CashDirection.RECEIVED, description = "Card settlement")
        val reconciliation = BankReconciliation.create(
            cashAccount.id, TODAY, Money(BigDecimal("500.00"), GBP), listOf(statementLine), emptyList(), GBP
        )

        bankReconciliationRepository.save(reconciliation, companyId)
        val loaded = bankReconciliationRepository.findById(reconciliation.id, companyId, emptyList())
            ?: error("Expected reconciliation to be found")

        loaded.accountId shouldBe cashAccount.id
        loaded.statementDate shouldBe TODAY
        loaded.statementEndingBalance shouldBe Money(BigDecimal("500.00"), GBP)
        loaded.currency shouldBe GBP
        loaded.statementLines.single().description shouldBe "Card settlement"
        loaded.statementLines.single().amount shouldBe Money(BigDecimal("500.00"), GBP)
        loaded.statementLines.single().direction shouldBe CashDirection.RECEIVED
    }

    @Test
    fun `given a new reconciliation, when saved and reloaded, then it is OPEN (V32 status column)`() {
        val companyId = newCompany()
        val cashAccount = newCashAccount(companyId)
        val reconciliation = BankReconciliation.create(cashAccount.id, TODAY, Money(BigDecimal("0.00"), GBP), emptyList(), emptyList(), GBP)

        bankReconciliationRepository.save(reconciliation, companyId)

        bankReconciliationRepository.findById(reconciliation.id, companyId, emptyList())!!.status shouldBe BankReconciliationStatus.OPEN
    }

    @Test
    fun `given a completed or cancelled reconciliation, when saved again and reloaded, then the final status persists and refuses further changes`() {
        val companyId = newCompany()
        val cashAccount = newCashAccount(companyId)
        val entry = postedEntry(companyId, cashAccount.id, "500.00")
        val statementLine = BankStatementLine(date = TODAY, amount = Money(BigDecimal("500.00"), GBP), direction = CashDirection.RECEIVED, description = "Card settlement")
        val toComplete = BankReconciliation.create(cashAccount.id, TODAY, Money(BigDecimal("500.00"), GBP), listOf(statementLine), listOf(entry), GBP)
        bankReconciliationRepository.save(toComplete, companyId) // first save inserts it OPEN
        toComplete.match(statementLine.id, entry.id)
        toComplete.complete()
        bankReconciliationRepository.save(toComplete, companyId) // second save must UPDATE the status

        val reloaded = bankReconciliationRepository.findById(toComplete.id, companyId, listOf(entry))!!
        reloaded.status shouldBe BankReconciliationStatus.COMPLETED
        reloaded.currentMatches shouldBe setOf(statementLine.id to entry.id)
        reloaded.unmatch(statementLine.id, entry.id).isValid shouldBe false

        val toCancel = BankReconciliation.create(cashAccount.id, TODAY, Money(BigDecimal("0.00"), GBP), emptyList(), emptyList(), GBP)
        bankReconciliationRepository.save(toCancel, companyId)
        toCancel.cancel()
        bankReconciliationRepository.save(toCancel, companyId)
        bankReconciliationRepository.findById(toCancel.id, companyId, emptyList())!!.status shouldBe BankReconciliationStatus.CANCELLED
    }

    @Test
    fun `given a reconciliation for a different Company, when found by id with the wrong Company, then it returns null`() {
        val companyId = newCompany()
        val otherCompanyId = newCompany()
        val cashAccount = newCashAccount(companyId)
        val reconciliation = BankReconciliation.create(
            cashAccount.id, TODAY, Money(BigDecimal("0.00"), GBP), emptyList(), emptyList(), GBP
        )
        bankReconciliationRepository.save(reconciliation, companyId)

        val loaded = bankReconciliationRepository.findById(reconciliation.id, otherCompanyId, emptyList())

        loaded shouldBe null
    }

    @Test
    fun `given a matched pair, when saved and reloaded, then the match round-trips`() {
        val companyId = newCompany()
        val cashAccount = newCashAccount(companyId)
        val entry = postedEntry(companyId, cashAccount.id, "500.00")
        val statementLine = BankStatementLine(date = TODAY, amount = Money(BigDecimal("500.00"), GBP), direction = CashDirection.RECEIVED, description = "Card settlement")
        val reconciliation = BankReconciliation.create(
            cashAccount.id, TODAY, Money(BigDecimal("500.00"), GBP), listOf(statementLine), listOf(entry), GBP
        )
        reconciliation.match(statementLine.id, entry.id)
        bankReconciliationRepository.save(reconciliation, companyId)

        val loaded = bankReconciliationRepository.findById(reconciliation.id, companyId, listOf(entry))!!

        loaded.isFullyReconciled shouldBe true
        loaded.currentMatches shouldBe setOf(statementLine.id to entry.id)
    }

    @Test
    fun `given reconciliations for two Companies, when listed by company, then only the requested Company's reconciliations come back`() {
        val companyId = newCompany()
        val otherCompanyId = newCompany()
        val cashAccount = newCashAccount(companyId)
        val otherCashAccount = newCashAccount(otherCompanyId)
        val reconciliation = BankReconciliation.create(cashAccount.id, TODAY, Money(BigDecimal("0.00"), GBP), emptyList(), emptyList(), GBP)
        val otherReconciliation = BankReconciliation.create(otherCashAccount.id, TODAY, Money(BigDecimal("0.00"), GBP), emptyList(), emptyList(), GBP)
        bankReconciliationRepository.save(reconciliation, companyId)
        bankReconciliationRepository.save(otherReconciliation, otherCompanyId)

        val results = bankReconciliationRepository.findAllByCompany(companyId, emptyList())

        results.map { it.id } shouldBe listOf(reconciliation.id)
    }

    @Test
    fun `given reconciliations for two Accounts in one Company, when listed filtered by Account, then only that Account's reconciliations come back`() {
        val companyId = newCompany()
        val cashAccount = newCashAccount(companyId)
        val otherAccount = newCashAccount(companyId)
        val reconciliation = BankReconciliation.create(cashAccount.id, TODAY, Money(BigDecimal("0.00"), GBP), emptyList(), emptyList(), GBP)
        val otherReconciliation = BankReconciliation.create(otherAccount.id, TODAY, Money(BigDecimal("0.00"), GBP), emptyList(), emptyList(), GBP)
        bankReconciliationRepository.save(reconciliation, companyId)
        bankReconciliationRepository.save(otherReconciliation, companyId)

        val results = bankReconciliationRepository.findAllByCompany(companyId, emptyList(), cashAccount.id)

        results.map { it.id } shouldBe listOf(reconciliation.id)
    }

    @Test
    fun `given a saved match, when unmatched and saved again, then the pairing is gone on reload`() {
        val companyId = newCompany()
        val cashAccount = newCashAccount(companyId)
        val entry = postedEntry(companyId, cashAccount.id, "500.00")
        val statementLine = BankStatementLine(date = TODAY, amount = Money(BigDecimal("500.00"), GBP), direction = CashDirection.RECEIVED, description = "Card settlement")
        val reconciliation = BankReconciliation.create(
            cashAccount.id, TODAY, Money(BigDecimal("500.00"), GBP), listOf(statementLine), listOf(entry), GBP
        )
        reconciliation.match(statementLine.id, entry.id)
        bankReconciliationRepository.save(reconciliation, companyId)
        reconciliation.unmatch(statementLine.id, entry.id)
        bankReconciliationRepository.save(reconciliation, companyId)

        val loaded = bankReconciliationRepository.findById(reconciliation.id, companyId, listOf(entry))!!

        loaded.isFullyReconciled shouldBe false
        loaded.currentMatches shouldBe emptySet()
    }
}
