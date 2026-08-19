package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.sales.Customer
import com.theprodeogroup.fish.domain.sales.CustomerRepository
import com.theprodeogroup.fish.domain.sales.SalesOrder
import com.theprodeogroup.fish.domain.sales.SalesOrderId
import com.theprodeogroup.fish.domain.sales.SalesOrderRepository

/**
 * Outcome of [PostSalesOrderUseCase.execute] - a sealed `Result`, same
 * reasoning as `PostPurchaseOrderResult` and every other multi-reason,
 * high-stakes posting use case this codebase has built.
 *
 * **Only carries "not found"/"invalid" cases for identifiers or values
 * supplied directly as raw [PostSalesOrderUseCase.Request] input**
 * ([SalesOrderId], [PeriodId], the AR control [AccountId], [Request.lineIndex],
 * and the two optional inventory [AccountId]s) - not for identifiers that
 * come from an already-persisted aggregate's own fields (the
 * `SalesOrder`'s `customerId`, a line's `accountId`, a `GOODS` line's
 * `stockItemId`). Same "no repository supports `delete()`" reasoning as
 * `PostPurchaseOrderUseCase` - those are internal invariants
 * (`checkNotNull`), not `Result` branches.
 */
sealed class PostSalesOrderResult {
    data class Success(
        val salesOrder: SalesOrder,
        val customer: Customer,
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : PostSalesOrderResult()
    data object SalesOrderNotFound : PostSalesOrderResult()
    data object PeriodNotFound : PostSalesOrderResult()
    data object PeriodNotOpen : PostSalesOrderResult()
    data class ArControlAccountNotFound(val accountId: AccountId) : PostSalesOrderResult()
    data object InvalidLineIndex : PostSalesOrderResult()
    data object LineAlreadyDelivered : PostSalesOrderResult()
    data object GoodsLineRequiresInventoryAccounts : PostSalesOrderResult()
    data class InventoryAccountNotFound(val accountId: AccountId) : PostSalesOrderResult()
    data object InsufficientStock : PostSalesOrderResult()
}

/**
 * Wraps `SalesOrder.deliverLine()` (docs/DDD_Design.md Section 2.5) - the
 * AR mirror of `PostPurchaseOrderUseCase`, but **scoped to one line per
 * call, not the whole order**, confirmed with the user before building:
 * `deliverLine()` itself recognizes exactly one line's worth of income
 * per call by design (supports genuinely progressive/staged delivery,
 * unlike `PurchaseOrder.send()`'s single atomic call for every line) -
 * there is no single domain call that posts "the whole order" the way
 * `PurchaseOrder.send()` does, so a caller delivering a multi-line order
 * in one shipment calls this use case once per line, exactly matching
 * `deliverLine()`'s own KDoc'd caller pattern.
 *
 * **Posts the resulting `JournalEntry` immediately, not just calls
 * `deliverLine()`** - same reasoning as `PostPurchaseOrderUseCase`:
 * `deliverLine()` alone only ever produces a `Draft` entry, which
 * wouldn't affect account balances at all.
 *
 * **Validates everything the use case itself can check before ever
 * calling `deliverLine()`**, so that a `null` return from `deliverLine()`
 * can only mean one thing: insufficient stock for a `GOODS` line's
 * `StockItem.recordIssue()`. Every other reason `deliverLine()` could
 * return `null` (invalid line index, already delivered, a mismatched
 * `Customer`, a missing/mismatched `StockItem`, missing inventory
 * `Account`s) is ruled out by this use case's own checks first - the
 * same "rule out every other cause first, so a remaining `null` has
 * exactly one meaning" reasoning `PostPurchaseOrderUseCase` used for
 * `PurchaseOrderNotDraft`.
 *
 * **The two inventory `Account`s (`cogsExpenseAccountId`/
 * `inventoryAssetAccountId`) are raw caller-supplied [Request] fields,
 * not persisted references** - only needed, and only validated, for a
 * `GOODS` line. A `SERVICE` line ignores them entirely, matching
 * `deliverLine()`'s own parameter treatment.
 */
class PostSalesOrderUseCase(
    private val salesOrderRepository: SalesOrderRepository,
    private val customerRepository: CustomerRepository,
    private val stockItemRepository: StockItemRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val salesOrderId: SalesOrderId,
        val lineIndex: Int,
        val periodId: PeriodId,
        val arControlAccountId: AccountId,
        val cogsExpenseAccountId: AccountId? = null,
        val inventoryAssetAccountId: AccountId? = null
    )

    fun execute(request: Request): PostSalesOrderResult {
        val salesOrder = salesOrderRepository.findById(request.salesOrderId)
            ?: return PostSalesOrderResult.SalesOrderNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return PostSalesOrderResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return PostSalesOrderResult.PeriodNotOpen
        }

        val arControlAccount = accountRepository.findById(request.arControlAccountId)
            ?: return PostSalesOrderResult.ArControlAccountNotFound(request.arControlAccountId)

        if (request.lineIndex !in salesOrder.lines.indices) {
            return PostSalesOrderResult.InvalidLineIndex
        }
        if (request.lineIndex in salesOrder.deliveredLineIndicesSnapshot()) {
            return PostSalesOrderResult.LineAlreadyDelivered
        }
        val line = salesOrder.lines[request.lineIndex]

        var cogsExpenseAccount: Account? = null
        var inventoryAssetAccount: Account? = null
        val stockItem = if (line.itemType == LineItemType.GOODS) {
            if (request.cogsExpenseAccountId == null || request.inventoryAssetAccountId == null) {
                return PostSalesOrderResult.GoodsLineRequiresInventoryAccounts
            }
            cogsExpenseAccount = accountRepository.findById(request.cogsExpenseAccountId)
                ?: return PostSalesOrderResult.InventoryAccountNotFound(request.cogsExpenseAccountId)
            inventoryAssetAccount = accountRepository.findById(request.inventoryAssetAccountId)
                ?: return PostSalesOrderResult.InventoryAccountNotFound(request.inventoryAssetAccountId)
            checkNotNull(stockItemRepository.findById(line.stockItemId!!)) {
                "PostSalesOrderUseCase found a SalesOrderLine referencing a StockItem " +
                    "(${line.stockItemId!!.value}) that no longer exists"
            }
        } else {
            null
        }

        val customer = checkNotNull(customerRepository.findById(salesOrder.customerId)) {
            "PostSalesOrderUseCase found a SalesOrder (${salesOrder.id.value}) referencing a Customer " +
                "(${salesOrder.customerId.value}) that no longer exists"
        }
        val revenueAccount = checkNotNull(accountRepository.findById(line.accountId)) {
            "PostSalesOrderUseCase found a SalesOrderLine referencing an Account " +
                "(${line.accountId.value}) that no longer exists"
        }

        val entry = salesOrder.deliverLine(
            request.lineIndex, customer, request.arControlAccountId, request.periodId,
            stockItem, request.cogsExpenseAccountId, request.inventoryAssetAccountId
        ) ?: return PostSalesOrderResult.InsufficientStock

        val posting = entry.post()
        check(posting.isValid) {
            "PostSalesOrderUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts = listOfNotNull(revenueAccount, arControlAccount, cogsExpenseAccount, inventoryAssetAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        salesOrderRepository.save(salesOrder)
        customerRepository.save(customer)
        stockItem?.let { stockItemRepository.save(it) }
        journalEntryRepository.save(entry)

        return PostSalesOrderResult.Success(salesOrder, customer, entry, entry.pullDomainEvents())
    }
}
