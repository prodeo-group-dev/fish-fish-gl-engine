package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.inventory.StockShortageEscalation
import com.theprodeogroup.fish.domain.inventory.StockShortageEscalationRepository
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal

/**
 * Outcome of [IssueStockForSaleUseCase.execute].
 */
sealed class IssueStockForSaleResult {
    data class Success(val committedCost: Money) : IssueStockForSaleResult()
    data class StockItemNotFound(val stockItemId: StockItemId) : IssueStockForSaleResult()
    data class InsufficientStock(
        val stockItemId: StockItemId,
        val requestedQuantity: BigDecimal,
        val quantityOnHand: BigDecimal
    ) : IssueStockForSaleResult()
}

/**
 * The same GOODS-sale stock check [CreateSalesInvoiceUseCase] already
 * performs internally, extracted into its own callable interface - not
 * shared code with that use case (deliberately duplicated, same
 * "resolution logic copied, not shared" reasoning as
 * [ComputeSalesPostingContextUseCase]'s own KDoc), so this use case
 * exists purely to let an out-of-repo caller (SOP, specifically -
 * closing its own "how does a caller with no direct StockItem access
 * check stock" gap) perform the same check-then-mutate: if
 * [StockItemId]'s `quantityOnHand` covers the requested quantity, it's
 * decremented and saved here; if it doesn't, a [StockShortageEscalation]
 * is always persisted first, then the sale is hard-rejected unless
 * [Request.callerCanOverrideStockCheck] is `true`, in which case the
 * shortfall is left visible and [committedCost] is still returned.
 *
 * [Request.requestedByEmail]/[Request.callerCanOverrideStockCheck] are
 * resolved by the caller from its own authenticated identity - this
 * use case takes plain values, no auth types, matching every other use
 * case in this package.
 */
class IssueStockForSaleUseCase(
    private val stockItemRepository: StockItemRepository,
    private val stockShortageEscalationRepository: StockShortageEscalationRepository
) {
    data class Request(
        val companyId: CompanyId,
        val stockItemId: StockItemId,
        val quantity: BigDecimal,
        val requestedByEmail: String,
        val callerCanOverrideStockCheck: Boolean = false
    )

    fun execute(request: Request): IssueStockForSaleResult {
        val stockItem = stockItemRepository.findById(request.stockItemId)
            ?.takeIf { it.companyId == request.companyId }
            ?: return IssueStockForSaleResult.StockItemNotFound(request.stockItemId)

        val quantityOnHandBeforeIssue = stockItem.quantityOnHand
        val committedCost = stockItem.unitCost * request.quantity

        val issueResult = stockItem.recordIssue(request.quantity)
        if (issueResult.isValid) {
            stockItemRepository.save(stockItem)
            return IssueStockForSaleResult.Success(committedCost)
        }

        stockShortageEscalationRepository.save(
            StockShortageEscalation.create(
                request.companyId, request.stockItemId, request.quantity, quantityOnHandBeforeIssue,
                request.requestedByEmail, overridden = request.callerCanOverrideStockCheck
            )
        )
        if (!request.callerCanOverrideStockCheck) {
            return IssueStockForSaleResult.InsufficientStock(request.stockItemId, request.quantity, quantityOnHandBeforeIssue)
        }
        return IssueStockForSaleResult.Success(committedCost)
    }
}
