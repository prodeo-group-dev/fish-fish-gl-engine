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
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Outcome of [PostInventoryIssueUseCase.execute] - the mirror-image
 * sealed `Result` to `PostInventoryReceiptResult` (Section 10.15).
 * `InvalidIssue` surfaces `StockItem.recordIssue()`'s own
 * `ValidationResult` errors directly (non-positive quantity, or more
 * than [StockItem.quantityOnHand]) - both wrap raw [Request] input
 * (`quantityIssued`, evaluated against the `StockItem`'s current state),
 * same treatment `InvalidReceipt` gives `recordReceipt()`'s errors.
 */
sealed class PostInventoryIssueResult {
    data class Success(
        val stockItem: StockItem,
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : PostInventoryIssueResult()
    data object StockItemNotFound : PostInventoryIssueResult()
    data object PeriodNotFound : PostInventoryIssueResult()
    data object PeriodNotOpen : PostInventoryIssueResult()
    data class InventoryAssetAccountNotFound(val accountId: AccountId) : PostInventoryIssueResult()
    data class ContraAccountNotFound(val accountId: AccountId) : PostInventoryIssueResult()
    data class InvalidIssue(val errors: List<String>) : PostInventoryIssueResult()
}

/**
 * Records a **standalone** inventory issue - one not driven by a
 * `SalesOrder` (that flow is `PostSalesOrderUseCase`, Section 10.14,
 * which already posts revenue and COGS together via `deliverLine()`).
 * Covers stock leaving the books for any reason other than a sale -
 * shrinkage, damage, loss, samples given away, internal consumption.
 * The mirror image of `PostInventoryReceiptUseCase` (Section 10.15):
 * same scenario-agnostic reasoning (debit a caller-supplied contra
 * Account, credit Inventory Asset - the mechanics don't depend on
 * *why* stock left), same choice to build the `JournalEntry` directly
 * in the use case rather than a new `StockItem` domain method
 * (`recordIssue()`'s own KDoc already settles this: "Unit cost is
 * unchanged - only receipts move the weighted average," consistent
 * with every `StockItem` posting method's "posting stays a caller
 * concern" design).
 *
 * **The issued cost is derived from the `StockItem`'s own current
 * `unitCost`, not supplied by the caller** - unlike `recordReceipt()`,
 * `recordIssue()` takes no cost parameter at all (an issue can't create
 * a new cost basis, only a receipt can move the weighted average), so
 * there is nothing for a caller to supply here. This is the one place
 * `PostInventoryIssueUseCase`'s `Request` is asymmetric with
 * `PostInventoryReceiptUseCase`'s.
 *
 * **Validates before acting**: `StockItem`/`Period`/both `Account`s are
 * all resolved and checked before `StockItem.recordIssue()` is ever
 * called - `recordIssue()` is itself safe to call speculatively (it
 * validates internally before mutating quantity), but this keeps the
 * ordering consistent with every other posting use case's discipline.
 */
class PostInventoryIssueUseCase(
    private val stockItemRepository: StockItemRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val stockItemId: StockItemId,
        val quantityIssued: BigDecimal,
        val inventoryAssetAccountId: AccountId,
        val contraAccountId: AccountId,
        val periodId: PeriodId,
        val date: LocalDate
    )

    fun execute(request: Request): PostInventoryIssueResult {
        val stockItem = stockItemRepository.findById(request.stockItemId)
            ?: return PostInventoryIssueResult.StockItemNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return PostInventoryIssueResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return PostInventoryIssueResult.PeriodNotOpen
        }

        val inventoryAssetAccount = accountRepository.findById(request.inventoryAssetAccountId)
            ?: return PostInventoryIssueResult.InventoryAssetAccountNotFound(request.inventoryAssetAccountId)
        val contraAccount = accountRepository.findById(request.contraAccountId)
            ?: return PostInventoryIssueResult.ContraAccountNotFound(request.contraAccountId)

        val costPerUnit = stockItem.unitCost
        val issue = stockItem.recordIssue(request.quantityIssued)
        if (!issue.isValid) {
            return PostInventoryIssueResult.InvalidIssue(issue.errors)
        }

        val totalCost = costPerUnit * request.quantityIssued
        val lines = listOf(
            JournalLine(request.contraAccountId, totalCost, TransactionSide.DEBIT),
            JournalLine(request.inventoryAssetAccountId, totalCost, TransactionSide.CREDIT)
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.MANUAL,
            "Inventory issue - ${stockItem.name} (${stockItem.id.value})"
        )
        val posting = entry.post()
        check(posting.isValid) {
            "PostInventoryIssueUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        inventoryAssetAccount.recordActivity()
        accountRepository.save(inventoryAssetAccount)
        contraAccount.recordActivity()
        accountRepository.save(contraAccount)

        stockItemRepository.save(stockItem)
        journalEntryRepository.save(entry)

        return PostInventoryIssueResult.Success(stockItem, entry, entry.pullDomainEvents())
    }
}
