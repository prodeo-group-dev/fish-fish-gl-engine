package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outcome of [PostInventoryReceiptUseCase.execute] - a sealed `Result`,
 * same reasoning as every other multi-reason, high-stakes posting use
 * case this codebase has built. `InvalidReceipt` surfaces
 * `StockItem.recordReceipt()`'s own `ValidationResult` errors directly,
 * the same treatment `PostJournalEntryResult.InvalidLines` gives
 * `JournalEntry.validateLines()`'s errors - both wrap raw [Request]
 * input (`quantityReceived`/`costReceived`), not a persisted reference.
 */
sealed class PostInventoryReceiptResult {
    data class Success(
        val stockItem: StockItem,
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : PostInventoryReceiptResult()
    data object StockItemNotFound : PostInventoryReceiptResult()
    data object PeriodNotFound : PostInventoryReceiptResult()
    data object PeriodNotOpen : PostInventoryReceiptResult()
    data class InventoryAssetAccountNotFound(val accountId: AccountId) : PostInventoryReceiptResult()
    data class ContraAccountNotFound(val accountId: AccountId) : PostInventoryReceiptResult()
    data class InvalidReceipt(val errors: List<String>) : PostInventoryReceiptResult()
}

/**
 * Records a **standalone** inventory receipt - one not driven by a
 * `PurchaseOrder` (that flow is `PostPurchaseOrderUseCase`, Section
 * 10.13, which already posts the Ledger effect via `PurchaseOrder.send()`
 * itself). Covers whatever real-world event puts stock on the books
 * without a purchase behind it - an opening/initial stock balance at
 * onboarding, a physical stock-count correction, found inventory, etc.
 * The mechanics are identical regardless of *why* - debit the Inventory
 * Asset Account, credit a caller-supplied contra Account - so this use
 * case doesn't bake in which scenario it's for, matching `CashBookEntry`'s
 * existing precedent for a standalone value movement with a
 * caller-supplied counter-account and no assumed business scenario.
 *
 * **Builds the `JournalEntry` directly in the use case, not via a new
 * `StockItem` domain method - a deliberate precedent choice between two
 * that already coexist in this codebase.** `PurchaseOrder.send()`/
 * `SalesOrder.deliverLine()` build their own `JournalEntry` inside the
 * domain aggregate; `PostJournalEntryUseCase` instead takes raw
 * `JournalLine`s and builds the entry directly in `application`. This
 * use case follows the second precedent, because `StockItem.recordReceipt()`
 * *already* made that choice for itself, explicitly and deliberately -
 * its own KDoc: "none of these post a `JournalEntry`... that stays a
 * caller concern," the same design `recordIssue()`/`consumeInto()`/
 * `addProductionCost()`/`completeInto()` all share. Adding a new
 * `StockItem.receive()` method that built its own `JournalEntry` would
 * contradict that already-settled design rather than complete it - this
 * use case is what "caller concern" always meant.
 *
 * **Validates before acting**: `StockItem`/`Period`/both `Account`s are
 * all resolved and checked before `StockItem.recordReceipt()` is ever
 * called - `recordReceipt()` is itself safe to call speculatively (it
 * validates internally before mutating), but checking the cheaper,
 * externally-visible conditions first keeps this use case's ordering
 * consistent with every other posting use case's discipline.
 */
class PostInventoryReceiptUseCase(
    private val stockItemRepository: StockItemRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val stockItemId: StockItemId,
        val quantityReceived: BigDecimal,
        val costReceived: Money,
        val inventoryAssetAccountId: AccountId,
        val contraAccountId: AccountId,
        val periodId: PeriodId,
        val date: LocalDate
    )

    fun execute(request: Request): PostInventoryReceiptResult {
        val stockItem = stockItemRepository.findById(request.stockItemId)
            ?: return PostInventoryReceiptResult.StockItemNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return PostInventoryReceiptResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return PostInventoryReceiptResult.PeriodNotOpen
        }

        val inventoryAssetAccount = accountRepository.findById(request.inventoryAssetAccountId)
            ?: return PostInventoryReceiptResult.InventoryAssetAccountNotFound(request.inventoryAssetAccountId)
        val contraAccount = accountRepository.findById(request.contraAccountId)
            ?: return PostInventoryReceiptResult.ContraAccountNotFound(request.contraAccountId)

        val receipt = stockItem.recordReceipt(request.quantityReceived, request.costReceived)
        if (!receipt.isValid) {
            return PostInventoryReceiptResult.InvalidReceipt(receipt.errors)
        }

        val totalCost = request.costReceived * request.quantityReceived
        val lines = listOf(
            JournalLine(request.inventoryAssetAccountId, totalCost, TransactionSide.DEBIT),
            JournalLine(request.contraAccountId, totalCost, TransactionSide.CREDIT)
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.MANUAL,
            "Inventory receipt - ${stockItem.name} (${stockItem.id.value})"
        )
        val posting = entry.post()
        check(posting.isValid) {
            "PostInventoryReceiptUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        inventoryAssetAccount.recordActivity()
        accountRepository.save(inventoryAssetAccount)
        contraAccount.recordActivity()
        accountRepository.save(contraAccount)

        stockItemRepository.save(stockItem)
        journalEntryRepository.save(entry)

        return PostInventoryReceiptResult.Success(stockItem, entry, entry.pullDomainEvents())
    }
}
