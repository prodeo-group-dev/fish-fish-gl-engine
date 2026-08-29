package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.common.Money
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * The "Schedule of Inventory" report behind the IM dashboard tab
 * (2026-08-29) - a thin projection of already-computed [StockItem]
 * state, no new domain invariants (see [InventorySchedule]'s own KDoc).
 */
class ComputeInventoryScheduleUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val stockItemRepository = FakeStockItemRepository()
    private val useCase = ComputeInventoryScheduleUseCase(companyRepository, stockItemRepository)

    private val company = Company.create(com.theprodeogroup.fish.domain.tenancy.TenantId.generate(), "Test Co", ClientType.SOLE_TRADER, "GB", GBP)
        .also { companyRepository.save(it) }

    @Test
    fun `given two StockItems for the Company, when executed, then it returns a line per item and grand totals`() {
        val itemA = StockItem.create(company.id, "Bag of rice", GBP).also {
            it.recordReceipt(BigDecimal("10"), Money(BigDecimal("5.00"), GBP))
            stockItemRepository.save(it)
        }
        val itemB = StockItem.create(company.id, "Bottle of oil", GBP).also {
            it.recordReceipt(BigDecimal("4"), Money(BigDecimal("12.50"), GBP))
            stockItemRepository.save(it)
        }

        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<ComputeInventoryScheduleUseCase.Result.Success>()
        success.schedule.lines.map { it.stockItemId } shouldBe listOf(itemA.id, itemB.id)
        success.schedule.totalCost shouldBe Money(BigDecimal("100.00"), GBP)
        success.schedule.totalCarryingValue shouldBe Money(BigDecimal("100.00"), GBP)
    }

    @Test
    fun `given no StockItems for the Company, when executed, then it returns an empty schedule with zero totals`() {
        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<ComputeInventoryScheduleUseCase.Result.Success>()
        success.schedule.lines shouldBe emptyList()
        success.schedule.totalCost shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a nonexistent Company, when executed, then it returns CompanyNotFound`() {
        val result = useCase.execute(com.theprodeogroup.fish.domain.tenancy.CompanyId.generate())

        result.shouldBeInstanceOf<ComputeInventoryScheduleUseCase.Result.CompanyNotFound>()
    }
}
