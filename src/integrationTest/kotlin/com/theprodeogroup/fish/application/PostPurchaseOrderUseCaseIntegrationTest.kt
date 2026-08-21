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
import com.theprodeogroup.fish.domain.purchasing.Creditor
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrder
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderLine
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderStatus
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCreditorRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPurchaseOrderRepository
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
private val TODAY = LocalDate.of(2026, 8, 20)

/**
 * `PostPurchaseOrderUseCaseTest` (`src/test`) uses in-memory fakes -
 * exercises the same use case against real Exposed repositories and a
 * live Postgres database (docs/DDD_Design.md Section 10.13). Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class PostPurchaseOrderUseCaseIntegrationTest {

    private val purchaseOrderRepository = ExposedPurchaseOrderRepository()
    private val creditorRepository = ExposedCreditorRepository()
    private val stockItemRepository = ExposedStockItemRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val useCase = PostPurchaseOrderUseCase(
        purchaseOrderRepository, creditorRepository, stockItemRepository,
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
    fun `given a Draft PurchaseOrder with a GOODS line against a real database, when posted, then everything round-trips correctly`() {
        // A real, persisted Company - creditors/stock_items/purchase_orders.company_id
        // all carry real FKs, so a fabricated CompanyId that was never actually saved
        // (this test's original bug) fails loudly here, exactly what this integration
        // test exists to catch.
        val tenant = Tenant.onboard("PO Test Tenant ${java.util.UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "PO Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        val companyId = company.id
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        val inventory = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory")
        val apControl = Account.create(companyId, AccountType.LIABILITY, AccountClassification.CURRENT, "2100", "Accounts Payable")
        accountRepository.save(inventory)
        accountRepository.save(apControl)
        val supplier = Creditor.create(companyId, "Acme Supplies", GBP)
        creditorRepository.save(supplier)
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItemRepository.save(stockItem)
        val order = PurchaseOrder.create(
            companyId, supplier.id, TODAY,
            listOf(
                PurchaseOrderLine(
                    "10 Widgets", inventory.id, Money(BigDecimal("100.00"), GBP),
                    LineItemType.GOODS, BigDecimal("10"), stockItem.id
                )
            )
        )
        purchaseOrderRepository.save(order)

        val result = useCase.execute(PostPurchaseOrderUseCase.Request(order.id, period.id, apControl.id))

        val success = result.shouldBeInstanceOf<PostPurchaseOrderResult.Success>()

        val reloadedOrder = requireNotNull(purchaseOrderRepository.findById(order.id))
        reloadedOrder.status shouldBe PurchaseOrderStatus.SENT

        val reloadedCreditor = requireNotNull(creditorRepository.findById(supplier.id))
        reloadedCreditor.balance shouldBe Money(BigDecimal("100.00"), GBP)

        val reloadedEntry = requireNotNull(journalEntryRepository.findById(success.journalEntry.id))
        reloadedEntry.status shouldBe PostingStatus.POSTED

        val reloadedStockItem = requireNotNull(stockItemRepository.findById(stockItem.id))
        reloadedStockItem.quantityOnHand.compareTo(BigDecimal("10")) shouldBe 0
    }
}
