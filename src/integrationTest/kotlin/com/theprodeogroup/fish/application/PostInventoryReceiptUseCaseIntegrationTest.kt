package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
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
private val TODAY = LocalDate.of(2026, 8, 22)

/**
 * `PostInventoryReceiptUseCaseTest` (`src/test`) uses in-memory fakes -
 * exercises the same use case against real Exposed repositories and a
 * live Postgres database (docs/DDD_Design.md Section 10.15). Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class PostInventoryReceiptUseCaseIntegrationTest {

    private val stockItemRepository = ExposedStockItemRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val useCase = PostInventoryReceiptUseCase(
        stockItemRepository, periodRepository, accountRepository, journalEntryRepository
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
    fun `given a standalone opening-balance receipt against a real database, when posted, then everything round-trips correctly`() {
        // A real, persisted Company - stock_items.company_id carries a
        // real FK (see PostPurchaseOrderUseCaseIntegrationTest's own
        // note - the same class of bug this construction avoids).
        val tenant = Tenant.onboard("Inventory Test Tenant ${java.util.UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "Inventory Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        val companyId = company.id
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        val inventory = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory")
        val openingBalanceEquity = Account.create(companyId, AccountType.EQUITY, null, "3900", "Opening Balance Equity")
        accountRepository.save(inventory)
        accountRepository.save(openingBalanceEquity)
        val stockItem = StockItem.create(companyId, "Widget", GBP)
        stockItemRepository.save(stockItem)

        val result = useCase.execute(
            PostInventoryReceiptUseCase.Request(
                stockItem.id, BigDecimal("25"), Money(BigDecimal("4.00"), GBP),
                inventory.id, openingBalanceEquity.id, period.id, TODAY
            )
        )

        val success = result.shouldBeInstanceOf<PostInventoryReceiptResult.Success>()

        val reloadedStockItem = requireNotNull(stockItemRepository.findById(stockItem.id))
        reloadedStockItem.quantityOnHand.compareTo(BigDecimal("25")) shouldBe 0
        reloadedStockItem.unitCost shouldBe Money(BigDecimal("4.00"), GBP)

        val reloadedEntry = requireNotNull(journalEntryRepository.findById(success.journalEntry.id))
        reloadedEntry.status shouldBe PostingStatus.POSTED

        val reloadedInventoryAccount = requireNotNull(accountRepository.findById(inventory.id))
        reloadedInventoryAccount.validateDeletion().isValid shouldBe false
    }
}
