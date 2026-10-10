package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Currency

/**
 * The cash/bank kind (V33, docs/GL_Cash_And_Bank_Books_SRS.md FR-CB01) round-trips through a real Postgres, an
 * account without one stays null, and the database itself refuses a kind on a non-ASSET row. Skips (not fails)
 * if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class AccountCashBookKindIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()

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

    private fun newCompany(): CompanyId =
        Company.create(TenantId.generate(), "Cash Book Kind Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, Currency.getInstance("GBP"))
            .also { companyRepository.save(it) }.id

    @Test
    fun `given cash, bank and kind-less accounts, when saved and reloaded, then each kind round-trips and none stays null`() {
        val companyId = newCompany()
        accountRepository.save(Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH))
        accountRepository.save(Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK))
        accountRepository.save(Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Receivables"))

        val loaded = accountRepository.findAllByCompany(companyId).associateBy { it.code }

        loaded.getValue("1000").cashBookKind shouldBe CashBookKind.CASH
        loaded.getValue("1010").cashBookKind shouldBe CashBookKind.BANK
        loaded.getValue("1100").cashBookKind shouldBe null
    }

    @Test
    fun `given a saved account, when its kind is changed and cleared and saved again, then the new value is what loads`() {
        val companyId = newCompany()
        val account = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
        accountRepository.save(account)

        account.changeCashBookKind(CashBookKind.BANK)
        accountRepository.save(account)
        accountRepository.findById(account.id)!!.cashBookKind shouldBe CashBookKind.BANK

        account.changeCashBookKind(null)
        accountRepository.save(account)
        accountRepository.findById(account.id)!!.cashBookKind shouldBe null
    }

    @Test
    fun `given a non-asset row, when the database is asked to store a kind on it, then the CHECK constraint refuses`() {
        val companyId = newCompany()
        val sales = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        accountRepository.save(sales)

        val refused = runCatching {
            transaction {
                exec("UPDATE accounts SET cash_book_kind = 'BANK' WHERE id = '${sales.id.value}'")
            }
        }.isFailure

        refused shouldBe true
    }

    @Test
    fun `given entries on two accounts, when entries are found by account, then only the entries touching that account come back - with all their lines`() {
        val companyId = newCompany()
        val periodRepository = ExposedPeriodRepository()
        val journalEntryRepository = ExposedJournalEntryRepository()
        val period = com.theprodeogroup.fish.domain.ledger.Period.create(
            companyId, com.theprodeogroup.fish.domain.common.PeriodType.MONTH, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(30)
        )
        periodRepository.save(period)
        val bank = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK)
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH)
        val sales = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        listOf(bank, cash, sales).forEach { accountRepository.save(it) }
        val gbp = java.util.Currency.getInstance("GBP")
        fun post(debit: Account, credit: Account, amount: String) =
            com.theprodeogroup.fish.domain.ledger.JournalEntry.create(
                period.id, java.time.LocalDate.now(),
                listOf(
                    com.theprodeogroup.fish.domain.ledger.JournalLine(debit.id, com.theprodeogroup.common.Money(java.math.BigDecimal(amount), gbp), com.theprodeogroup.fish.domain.common.TransactionSide.DEBIT),
                    com.theprodeogroup.fish.domain.ledger.JournalLine(credit.id, com.theprodeogroup.common.Money(java.math.BigDecimal(amount), gbp), com.theprodeogroup.fish.domain.common.TransactionSide.CREDIT)
                ),
                com.theprodeogroup.fish.domain.common.JournalSource.MANUAL
            ).also { it.post(); journalEntryRepository.save(it) }
        val toBank = post(bank, sales, "100.00")
        val toCash = post(cash, sales, "40.00")
        val transfer = post(cash, bank, "10.00")

        val bankEntries = journalEntryRepository.findAllByAccount(bank.id)
        val cashEntries = journalEntryRepository.findAllByAccount(cash.id)

        bankEntries.map { it.id }.toSet() shouldBe setOf(toBank.id, transfer.id)
        cashEntries.map { it.id }.toSet() shouldBe setOf(toCash.id, transfer.id)
        bankEntries.first { it.id == transfer.id }.lines.size shouldBe 2
        journalEntryRepository.findAllByAccount(com.theprodeogroup.fish.domain.ledger.AccountId.generate()) shouldBe emptyList()
    }
}
