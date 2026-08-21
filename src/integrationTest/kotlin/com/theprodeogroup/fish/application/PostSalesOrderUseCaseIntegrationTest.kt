package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.sales.Customer
import com.theprodeogroup.fish.domain.sales.SalesOrder
import com.theprodeogroup.fish.domain.sales.SalesOrderLine
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCustomerRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedSalesOrderRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedStockItemRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 21)

/**
 * `PostSalesOrderUseCaseTest` (`src/test`) uses in-memory fakes -
 * exercises the same use case against real Exposed repositories and a
 * live Postgres database (docs/DDD_Design.md Section 10.14). Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class PostSalesOrderUseCaseIntegrationTest {

    private val salesOrderRepository = ExposedSalesOrderRepository()
    private val customerRepository = ExposedCustomerRepository()
    private val stockItemRepository = ExposedStockItemRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val useCase = PostSalesOrderUseCase(
        salesOrderRepository, customerRepository, stockItemRepository,
        periodRepository, accountRepository, journalEntryRepository
    )

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
    fun `given a Draft SalesOrder with a GOODS line against a real database, when posted, then everything round-trips correctly`() {
        // A real, persisted Company - customers/stock_items/sales_orders.company_id
        // all carry real FKs (see PostPurchaseOrderUseCaseIntegrationTest's own
        // note - the same class of bug this construction avoids).
        val tenant = Tenant.onboard("SO Test Tenant ${java.util.UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "SO Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        val companyId = company.id
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        val revenue = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales Revenue")
        val arControl = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1200", "Accounts Receivable")
        val cogsExpense = Account.create(companyId, AccountType.EXPENSE, null, "5010", "Cost of Goods Sold")
        val inventoryAsset = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory")
        accountRepository.save(revenue)
        accountRepository.save(arControl)
        accountRepository.save(cogsExpense)
        accountRepository.save(inventoryAsset)
        val buyer = Customer.create(companyId, "Acme Buyers", GBP)
        customerRepository.save(buyer)
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItem.recordReceipt(BigDecimal("10"), Money(BigDecimal("50.00"), GBP))
        stockItemRepository.save(stockItem)
        val order = SalesOrder.create(
            companyId, buyer.id, TODAY,
            listOf(
                SalesOrderLine(
                    "10 Widgets", revenue.id, Money(BigDecimal("150.00"), GBP),
                    LineItemType.GOODS, BigDecimal("10"), stockItem.id
                )
            )
        )
        salesOrderRepository.save(order)

        val result = useCase.execute(
            PostSalesOrderUseCase.Request(order.id, 0, period.id, arControl.id, cogsExpense.id, inventoryAsset.id)
        )

        val success = result.shouldBeInstanceOf<PostSalesOrderResult.Success>()

        val reloadedOrder = requireNotNull(salesOrderRepository.findById(order.id))
        reloadedOrder.status.name shouldBe "FULFILLED"

        val reloadedCustomer = requireNotNull(customerRepository.findById(buyer.id))
        reloadedCustomer.balance shouldBe Money(BigDecimal("150.00"), GBP)

        val reloadedEntry = requireNotNull(journalEntryRepository.findById(success.journalEntry.id))
        reloadedEntry.status shouldBe PostingStatus.POSTED

        val reloadedStockItem = requireNotNull(stockItemRepository.findById(stockItem.id))
        reloadedStockItem.quantityOnHand.compareTo(BigDecimal("0")) shouldBe 0
    }
}
