package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.inventory.InventoryStage
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.payroll.PayRun
import com.theprodeogroup.fish.domain.purchasing.Creditor
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrder
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderLine
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderStatus
import com.theprodeogroup.fish.domain.sales.AccountsReceivableAging
import com.theprodeogroup.fish.domain.sales.Customer
import com.theprodeogroup.fish.domain.sales.SalesOrder
import com.theprodeogroup.fish.domain.sales.SalesOrderLine
import com.theprodeogroup.fish.domain.sales.SalesOrderStatus
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
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
 * NUMERIC(19,4) always returns scale-4 BigDecimals on read, regardless of
 * the scale a value was written with (`BigDecimal("10")` in memory comes
 * back as `10.0000`) - inherent to a fixed-scale column type, the same
 * reason `Money` needed its own scale-independent `equals()`. `quantity`/
 * `quantityOnHand` are raw `BigDecimal`, not wrapped in `Money`, so
 * round-trip comparisons need this helper instead of `shouldBe`.
 */
private infix fun BigDecimal?.shouldEqualNumerically(other: BigDecimal?) {
    if (this == null || other == null) {
        this shouldBe other
    } else {
        (this.compareTo(other) == 0) shouldBe true
    }
}

private fun assertPurchaseOrderLinesMatchNumerically(actual: List<PurchaseOrderLine>, expected: List<PurchaseOrderLine>) {
    actual.size shouldBe expected.size
    actual.zip(expected).forEach { (a, e) ->
        a.description shouldBe e.description
        a.accountId shouldBe e.accountId
        a.amount shouldBe e.amount
        a.itemType shouldBe e.itemType
        a.quantity shouldEqualNumerically e.quantity
        a.stockItemId shouldBe e.stockItemId
    }
}

private fun assertSalesOrderLinesMatchNumerically(actual: List<SalesOrderLine>, expected: List<SalesOrderLine>) {
    actual.size shouldBe expected.size
    actual.zip(expected).forEach { (a, e) ->
        a.description shouldBe e.description
        a.accountId shouldBe e.accountId
        a.amount shouldBe e.amount
        a.itemType shouldBe e.itemType
        a.quantity shouldEqualNumerically e.quantity
        a.stockItemId shouldBe e.stockItemId
    }
}

/**
 * Verifies the ecosystem repositories (Purchase Order Processing, Sales
 * Order Processing, Inventory Management, Payroll - docs/DDD_Design.md
 * Section 10.4) genuinely round-trip through a real Postgres database,
 * same discipline as `CoreLedgerRepositoriesIntegrationTest`/
 * `TenancyRepositoriesIntegrationTest`. Skips (not fails) if
 * `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class EcosystemRepositoriesIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val tenantRepository = ExposedTenantRepository()
    private val accountRepository = ExposedAccountRepository()
    private val creditorRepository = ExposedCreditorRepository()
    private val purchaseOrderRepository = ExposedPurchaseOrderRepository()
    private val customerRepository = ExposedCustomerRepository()
    private val salesOrderRepository = ExposedSalesOrderRepository()
    private val stockItemRepository = ExposedStockItemRepository()
    private val payRunRepository = ExposedPayRunRepository()

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
        val tenant = Tenant.onboard("Ecosystem Test Co", TenantSegment.EXTERNAL_B2B, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "Ecosystem Test Co", ClientType.COMPANY_LIMITED, "GB", GBP)
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
    fun `given a sent PurchaseOrder with GOODS and SERVICE lines, when saved and reloaded, then status and lines round-trip`() {
        val companyId = newCompany()
        val creditor = Creditor.create(companyId, "Acme Supplies", GBP)
        creditorRepository.save(creditor)
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItemRepository.save(stockItem)
        val inventoryAccount = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory")
        val expenseAccount = Account.create(companyId, AccountType.EXPENSE, null, "5200", "Consulting")
        val apControlAccount = Account.create(companyId, AccountType.LIABILITY, AccountClassification.CURRENT, "2100", "Accounts Payable")
        accountRepository.save(inventoryAccount)
        accountRepository.save(expenseAccount)
        accountRepository.save(apControlAccount)

        val order = PurchaseOrder.create(
            companyId, creditor.id, TODAY,
            listOf(
                PurchaseOrderLine(
                    "10 Widgets", inventoryAccount.id, Money(BigDecimal("100.00"), GBP),
                    LineItemType.GOODS, BigDecimal("10"), stockItem.id
                ),
                PurchaseOrderLine(
                    "Consulting", expenseAccount.id, Money(BigDecimal("50.00"), GBP),
                    LineItemType.SERVICE
                )
            )
        )
        val period = com.theprodeogroup.fish.domain.ledger.Period.create(
            companyId, com.theprodeogroup.fish.domain.common.PeriodType.MONTH, TODAY, TODAY.plusDays(30)
        )
        val periodRepository = ExposedPeriodRepository()
        periodRepository.save(period)
        order.send(creditor, apControlAccount.id, period.id, listOf(stockItem))

        purchaseOrderRepository.save(order)
        val reloaded = requireNotNull(purchaseOrderRepository.findById(order.id))

        reloaded.id shouldBe order.id
        reloaded.companyId shouldBe companyId
        reloaded.creditorId shouldBe creditor.id
        reloaded.date shouldBe TODAY
        reloaded.status shouldBe PurchaseOrderStatus.SENT
        assertPurchaseOrderLinesMatchNumerically(reloaded.lines, order.lines)
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
    fun `given a SalesOrder with one delivered and one undelivered line, when saved and reloaded, then the computed status round-trips`() {
        val companyId = newCompany()
        val customer = Customer.create(companyId, "Beta Retail", GBP)
        customerRepository.save(customer)
        val stockItem = StockItem.create(companyId, "Gadget", GBP)
        stockItem.recordReceipt(BigDecimal("20"), Money(BigDecimal("10.00"), GBP))
        stockItemRepository.save(stockItem)
        val revenueAccount = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        val arControlAccount = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Accounts Receivable")
        val cogsAccount = Account.create(companyId, AccountType.EXPENSE, null, "5100", "Cost of Goods Sold")
        val inventoryAccount = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory")
        accountRepository.save(revenueAccount)
        accountRepository.save(arControlAccount)
        accountRepository.save(cogsAccount)
        accountRepository.save(inventoryAccount)
        val period = com.theprodeogroup.fish.domain.ledger.Period.create(
            companyId, com.theprodeogroup.fish.domain.common.PeriodType.MONTH, TODAY, TODAY.plusDays(30)
        )
        val periodRepository = ExposedPeriodRepository()
        periodRepository.save(period)

        val order = SalesOrder.create(
            companyId, customer.id, TODAY,
            listOf(
                SalesOrderLine(
                    "5 Gadgets", revenueAccount.id, Money(BigDecimal("200.00"), GBP),
                    LineItemType.GOODS, BigDecimal("5"), stockItem.id
                ),
                SalesOrderLine(
                    "10 Gadgets", revenueAccount.id, Money(BigDecimal("400.00"), GBP),
                    LineItemType.GOODS, BigDecimal("10"), stockItem.id
                )
            )
        )
        order.deliverLine(0, customer, arControlAccount.id, period.id, stockItem, cogsAccount.id, inventoryAccount.id)

        salesOrderRepository.save(order)
        val reloaded = requireNotNull(salesOrderRepository.findById(order.id))

        reloaded.id shouldBe order.id
        reloaded.companyId shouldBe companyId
        reloaded.customerId shouldBe customer.id
        reloaded.date shouldBe TODAY
        assertSalesOrderLinesMatchNumerically(reloaded.lines, order.lines)
        reloaded.status shouldBe SalesOrderStatus.PARTIALLY_DELIVERED
        reloaded.status shouldBe order.status
    }

    @Test
    fun `given a StockItem in the WORK_IN_PROGRESS stage with an NRV write-down, when saved and reloaded, then every field round-trips`() {
        val companyId = newCompany()
        val stockItem = StockItem.create(companyId, "Half-Finished Widget", GBP, InventoryStage.WORK_IN_PROGRESS)
        stockItem.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))
        val writeDownAccount = Account.create(companyId, AccountType.EXPENSE, null, "5400", "Inventory Write-Down")
        val inventoryAccount = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory")
        accountRepository.save(writeDownAccount)
        accountRepository.save(inventoryAccount)
        val period = com.theprodeogroup.fish.domain.ledger.Period.create(
            companyId, com.theprodeogroup.fish.domain.common.PeriodType.MONTH, TODAY, TODAY.plusDays(30)
        )
        val periodRepository = ExposedPeriodRepository()
        periodRepository.save(period)
        stockItem.assessNetRealisableValue(
            Money(BigDecimal("4.00"), GBP), writeDownAccount.id, inventoryAccount.id, period.id, TODAY
        )

        stockItemRepository.save(stockItem)
        val reloaded = requireNotNull(stockItemRepository.findById(stockItem.id))

        reloaded.id shouldBe stockItem.id
        reloaded.companyId shouldBe companyId
        reloaded.name shouldBe "Half-Finished Widget"
        reloaded.currency shouldBe GBP
        reloaded.stage shouldBe InventoryStage.WORK_IN_PROGRESS
        reloaded.quantityOnHand shouldEqualNumerically stockItem.quantityOnHand
        reloaded.unitCost shouldBe stockItem.unitCost
        reloaded.nrvWriteDownPerUnit shouldBe stockItem.nrvWriteDownPerUnit
    }

    @Test
    fun `given a PayRun with both wages and salaries, when saved and reloaded, then every field round-trips`() {
        val companyId = newCompany()
        val payRun = PayRun.create(
            companyId, TODAY, Money(BigDecimal("10000.00"), GBP), Money(BigDecimal("5000.00"), GBP)
        )

        payRunRepository.save(payRun)
        val reloaded = requireNotNull(payRunRepository.findById(payRun.id))

        reloaded.id shouldBe payRun.id
        reloaded.companyId shouldBe companyId
        reloaded.date shouldBe TODAY
        reloaded.totalWages shouldBe payRun.totalWages
        reloaded.totalSalaries shouldBe payRun.totalSalaries
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
