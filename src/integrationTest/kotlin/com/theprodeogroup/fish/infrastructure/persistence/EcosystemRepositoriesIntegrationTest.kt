package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.purchasing.Creditor
import com.theprodeogroup.fish.domain.sales.AccountsReceivableAging
import com.theprodeogroup.fish.domain.sales.Customer
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
private val TODAY = LocalDate.of(2026, 8, 19)

/**
 * Verifies the "ecosystem" repositories still owned by GL directly -
 * `Creditor`/`Customer` (docs/DDD_Design.md Section 10.4) - genuinely
 * round-trip through a real Postgres database, same discipline as
 * `CoreLedgerRepositoriesIntegrationTest`/`TenancyRepositoriesIntegrationTest`.
 * Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 *
 * **PurchaseOrder/SalesOrder/StockItem/PayRun coverage removed
 * 2026-09-02** - all four aggregates (and their repositories) were
 * retired from GL earlier this same session ("Retire GL's StockItem
 * from its legacy costing", "Retire GL's dead PostPayRunUseCase") in
 * favor of POP/SOP/IM's own thin posting interfaces and HR's own
 * `RecordPayRunUseCase` caller - this file just hadn't been re-run
 * against `compileIntegrationTestKotlin` since, so the break went
 * unnoticed until now.
 */
class EcosystemRepositoriesIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()
    private val creditorRepository = ExposedCreditorRepository()
    private val customerRepository = ExposedCustomerRepository()

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

    /** Every ecosystem table's `company_id` is a real FK - a saved Company is required before anything else in this suite. */
    private fun newCompany(): com.theprodeogroup.fish.domain.tenancy.CompanyId {
        val tenant = TenantId.generate()
        val company = Company.create(tenant, "Ecosystem Test Co", ClientType.COMPANY_LIMITED, "GB", GBP)
        companyRepository.save(company)
        return company.id
    }

    @Test
    fun `given a Creditor with a non-zero balance, when saved and reloaded, then the balance round-trips`() {
        val companyId = newCompany()
        val creditor = Creditor.create(companyId, "Acme Supplies", GBP)
        creditor.recordCharge(Money(BigDecimal("250.00"), GBP))

        creditorRepository.save(creditor)
        val reloaded = requireNotNull(creditorRepository.findById(creditor.id))

        reloaded.id shouldBe creditor.id
        reloaded.companyId shouldBe companyId
        reloaded.name shouldBe "Acme Supplies"
        reloaded.currency shouldBe GBP
        reloaded.balance shouldBe creditor.balance
    }

    @Test
    fun `given a Customer with ECL allowance recorded, when saved and reloaded, then both balance fields round-trip`() {
        val companyId = newCompany()
        val customer = Customer.create(companyId, "Beta Retail", GBP)
        customer.recordSale(Money(BigDecimal("500.00"), GBP))
        val revenueAccount = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        val arControlAccount = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable")
        val expenseAccount = Account.create(companyId, AccountType.EXPENSE, null, "5300", "Bad Debt Expense")
        val allowanceAccount = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1110", "ECL Allowance")
        accountRepository.save(revenueAccount)
        accountRepository.save(arControlAccount)
        accountRepository.save(expenseAccount)
        accountRepository.save(allowanceAccount)
        val period = com.theprodeogroup.fish.domain.ledger.Period.create(
            companyId, com.theprodeogroup.fish.domain.common.PeriodType.MONTH, TODAY, TODAY.plusDays(30)
        )
        val periodRepository = ExposedPeriodRepository()
        periodRepository.save(period)

        // A real posted JournalEntry, not an invented shortcut - AccountsReceivableAging
        // is derived entirely from posted entries with the CUSTOMER dimension tag
        // (Section 2.5), so the aging behind this ECL assessment has to come from one.
        val saleEntry = JournalEntry.create(
            period.id, TODAY,
            listOf(
                JournalLine(
                    arControlAccount.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT,
                    mapOf(DimensionType.CUSTOMER to customer.id.value.toString())
                ),
                JournalLine(revenueAccount.id, Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, "Sale to Beta Retail"
        )
        saleEntry.post()

        val aging = AccountsReceivableAging.of(
            customer.id, arControlAccount.id, listOf(saleEntry), TODAY, GBP
        )
        customer.assessExpectedCreditLoss(
            aging, mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.05")),
            expenseAccount.id, allowanceAccount.id, period.id, TODAY
        )

        customerRepository.save(customer)
        val reloaded = requireNotNull(customerRepository.findById(customer.id))

        reloaded.id shouldBe customer.id
        reloaded.balance shouldBe customer.balance
        reloaded.allowanceForExpectedCreditLoss shouldBe customer.allowanceForExpectedCreditLoss
    }

    @Test
    fun `given two Creditors under one Company, when found by company, then both are returned`() {
        val companyId = newCompany()
        val creditorA = Creditor.create(companyId, "Supplier A", GBP)
        val creditorB = Creditor.create(companyId, "Supplier B", GBP)
        creditorRepository.save(creditorA)
        creditorRepository.save(creditorB)

        val found = creditorRepository.findAllByCompany(companyId)

        found.map { it.id }.toSet() shouldBe setOf(creditorA.id, creditorB.id)
    }
}
