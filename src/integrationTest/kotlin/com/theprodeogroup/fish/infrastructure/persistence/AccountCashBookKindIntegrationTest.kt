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
}
