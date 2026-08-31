package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val USD: Currency = Currency.getInstance("USD")

/** [IssueStockForSaleUseCase] - the same check-then-mutate GOODS-sale stock check [CreateSalesInvoiceUseCase] does internally, exposed as its own callable interface. */
class IssueStockForSaleUseCaseTest {

    private fun stockItem(quantityOnHand: String, unitCost: String, companyId: CompanyId = CompanyId.generate()): StockItem {
        val item = StockItem.create(companyId, "Refined White Sugar", USD)
        item.recordReceipt(BigDecimal(quantityOnHand), Money(BigDecimal(unitCost), USD))
        return item
    }

    @Test
    fun `given sufficient stock, when executed, then it decrements quantityOnHand and returns the committed cost`() {
        val companyId = CompanyId.generate()
        val item = stockItem("100", "5.00", companyId)
        val stockItemRepository = FakeStockItemRepository().also { it.save(item) }
        val escalationRepository = FakeStockShortageEscalationRepository()
        val useCase = IssueStockForSaleUseCase(stockItemRepository, escalationRepository)

        val result = useCase.execute(
            IssueStockForSaleUseCase.Request(companyId, item.id, BigDecimal("10"), "sales@example.com")
        )

        val success = result.shouldBeInstanceOf<IssueStockForSaleResult.Success>()
        success.committedCost shouldBe Money(BigDecimal("50.00"), USD)
        stockItemRepository.findById(item.id)?.quantityOnHand shouldBe BigDecimal("90")
        escalationRepository.saveCalls.size shouldBe 0
    }

    @Test
    fun `given insufficient stock and no override, when executed, then it escalates and returns InsufficientStock without mutating quantity`() {
        val companyId = CompanyId.generate()
        val item = stockItem("5", "5.00", companyId)
        val stockItemRepository = FakeStockItemRepository().also { it.save(item) }
        val escalationRepository = FakeStockShortageEscalationRepository()
        val useCase = IssueStockForSaleUseCase(stockItemRepository, escalationRepository)

        val result = useCase.execute(
            IssueStockForSaleUseCase.Request(companyId, item.id, BigDecimal("10"), "sales@example.com")
        )

        val failure = result.shouldBeInstanceOf<IssueStockForSaleResult.InsufficientStock>()
        failure.quantityOnHand shouldBe BigDecimal("5")
        stockItemRepository.findById(item.id)?.quantityOnHand shouldBe BigDecimal("5")
        escalationRepository.saveCalls.size shouldBe 1
        escalationRepository.saveCalls.single().overridden shouldBe false
    }

    @Test
    fun `given insufficient stock with an override, when executed, then it escalates but still returns Success`() {
        val companyId = CompanyId.generate()
        val item = stockItem("5", "5.00", companyId)
        val stockItemRepository = FakeStockItemRepository().also { it.save(item) }
        val escalationRepository = FakeStockShortageEscalationRepository()
        val useCase = IssueStockForSaleUseCase(stockItemRepository, escalationRepository)

        val result = useCase.execute(
            IssueStockForSaleUseCase.Request(companyId, item.id, BigDecimal("10"), "sales@example.com", callerCanOverrideStockCheck = true)
        )

        val success = result.shouldBeInstanceOf<IssueStockForSaleResult.Success>()
        success.committedCost shouldBe Money(BigDecimal("50.00"), USD)
        escalationRepository.saveCalls.size shouldBe 1
        escalationRepository.saveCalls.single().overridden shouldBe true
    }

    @Test
    fun `given a StockItem belonging to a different Company, when executed, then it returns StockItemNotFound`() {
        val item = stockItem("100", "5.00", CompanyId.generate())
        val stockItemRepository = FakeStockItemRepository().also { it.save(item) }
        val useCase = IssueStockForSaleUseCase(stockItemRepository, FakeStockShortageEscalationRepository())

        val result = useCase.execute(
            IssueStockForSaleUseCase.Request(CompanyId.generate(), item.id, BigDecimal("10"), "sales@example.com")
        )

        result.shouldBeInstanceOf<IssueStockForSaleResult.StockItemNotFound>()
    }

    @Test
    fun `given a nonexistent StockItem, when executed, then it returns StockItemNotFound`() {
        val useCase = IssueStockForSaleUseCase(FakeStockItemRepository(), FakeStockShortageEscalationRepository())

        val result = useCase.execute(
            IssueStockForSaleUseCase.Request(CompanyId.generate(), StockItemId.generate(), BigDecimal("10"), "sales@example.com")
        )

        result.shouldBeInstanceOf<IssueStockForSaleResult.StockItemNotFound>()
    }
}
