package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.purchasing.Creditor
import com.theprodeogroup.fish.domain.purchasing.CreditorRepository
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrder
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderId
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderRepository

/**
 * Outcome of [PostPurchaseOrderUseCase.execute] - a sealed `Result`,
 * same reasoning as `PostJournalEntryResult`/`ClosePeriodResult`/
 * `ComputeTaxResult`/`ReverseJournalEntryResult` (Sections
 * 10.8/10.9/10.10/10.12): several genuinely distinct failure reasons
 * with real financial-integrity consequences.
 *
 * **Only carries "not found" cases for identifiers supplied directly as
 * raw [PostPurchaseOrderUseCase.Request] input** ([PurchaseOrderId],
 * [PeriodId], the AP control [AccountId]) - not for identifiers that
 * come from an already-persisted aggregate's own fields (the
 * `PurchaseOrder`'s `creditorId`, its lines' `accountId`s, a `GOODS`
 * line's `stockItemId`). No repository in this codebase supports
 * `delete()` at all, so a reference from one already-persisted
 * aggregate to another can never legitimately go stale - those are
 * handled as internal invariants (`checkNotNull`) inside [execute],
 * not `Result` branches. See `ReverseJournalEntryUseCase`'s KDoc
 * (Section 10.12) for the same reasoning applied once already.
 */
sealed class PostPurchaseOrderResult {
    data class Success(
        val purchaseOrder: PurchaseOrder,
        val creditor: Creditor,
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : PostPurchaseOrderResult()
    data object PurchaseOrderNotFound : PostPurchaseOrderResult()
    data object PeriodNotFound : PostPurchaseOrderResult()
    data object PeriodNotOpen : PostPurchaseOrderResult()
    data class ApControlAccountNotFound(val accountId: AccountId) : PostPurchaseOrderResult()
    data object PurchaseOrderNotDraft : PostPurchaseOrderResult()
}

/**
 * Wraps `PurchaseOrder.send()` (docs/DDD_Design.md Section 2.5) -
 * Increment 1 of the "ecosystem" finally gets an application-layer
 * entry point, matching the three core-Ledger use cases already built
 * (Sections 10.8-10.9, 10.12).
 *
 * **Posts the resulting `JournalEntry` immediately, not just `send()`s
 * it.** `PurchaseOrder.send()` itself only ever produces a `Draft`
 * `JournalEntry` (`JournalEntry.create()`'s own starting status) - it
 * never calls `.post()`. Left as `Draft`, the AP effect wouldn't
 * actually show up in account balances (`PostingStatus.affectsBalance()`
 * only counts `Posted`/`System`), contradicting Section 2.5's own "when
 * we order we owe" framing, which describes immediate recognition, not
 * a deferred approval step. No source material describes a separate
 * pending-approval workflow for Purchase Orders, so this use case
 * completes the posting atomically - matching its own name (`Post...`,
 * not `Send...`), the same verb this codebase has used throughout to
 * mean "make it affect the Ledger's balances."
 *
 * **Applies the same "no posting into a Closed Period" guard every
 * other posting use case enforces** - `PurchaseOrder`/`JournalEntry`
 * still know nothing about `Period` themselves (Section 3.1's design
 * note), so the guard lives here, checked before `send()` is ever
 * called.
 *
 * **Validates before acting**: the AP control `Account` and the target
 * `Period`'s openness are both checked before `PurchaseOrder.send()`
 * runs, so a rejected post never mutates the `PurchaseOrder`, `Creditor`,
 * or any `StockItem` at all.
 */
class PostPurchaseOrderUseCase(
    private val purchaseOrderRepository: PurchaseOrderRepository,
    private val creditorRepository: CreditorRepository,
    private val stockItemRepository: StockItemRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val purchaseOrderId: PurchaseOrderId,
        val periodId: PeriodId,
        val apControlAccountId: AccountId
    )

    fun execute(request: Request): PostPurchaseOrderResult {
        val purchaseOrder = purchaseOrderRepository.findById(request.purchaseOrderId)
            ?: return PostPurchaseOrderResult.PurchaseOrderNotFound

        val period = periodRepository.findById(request.periodId)
            ?: return PostPurchaseOrderResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return PostPurchaseOrderResult.PeriodNotOpen
        }

        val apControlAccount = accountRepository.findById(request.apControlAccountId)
            ?: return PostPurchaseOrderResult.ApControlAccountNotFound(request.apControlAccountId)

        val creditor = checkNotNull(creditorRepository.findById(purchaseOrder.creditorId)) {
            "PostPurchaseOrderUseCase found a PurchaseOrder (${purchaseOrder.id.value}) referencing a Creditor " +
                "(${purchaseOrder.creditorId.value}) that no longer exists"
        }

        val stockItems = purchaseOrder.lines
            .filter { it.itemType == LineItemType.GOODS }
            .mapNotNull { it.stockItemId }
            .distinct()
            .map { stockItemId ->
                checkNotNull(stockItemRepository.findById(stockItemId)) {
                    "PostPurchaseOrderUseCase found a PurchaseOrder (${purchaseOrder.id.value}) referencing a StockItem " +
                        "($stockItemId) that no longer exists"
                }
            }

        val entry = purchaseOrder.send(creditor, request.apControlAccountId, request.periodId, stockItems)
            ?: return PostPurchaseOrderResult.PurchaseOrderNotDraft

        val posting = entry.post()
        check(posting.isValid) {
            "PostPurchaseOrderUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val lineAccounts = purchaseOrder.lines.map { line ->
            checkNotNull(accountRepository.findById(line.accountId)) {
                "PostPurchaseOrderUseCase found a PurchaseOrderLine referencing an Account " +
                    "(${line.accountId.value}) that no longer exists"
            }
        }
        for (account in lineAccounts + apControlAccount) {
            account.recordActivity()
            accountRepository.save(account)
        }

        purchaseOrderRepository.save(purchaseOrder)
        creditorRepository.save(creditor)
        stockItems.forEach { stockItemRepository.save(it) }
        journalEntryRepository.save(entry)

        return PostPurchaseOrderResult.Success(purchaseOrder, creditor, entry, entry.pullDomainEvents())
    }
}
