package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import java.time.LocalDate

/**
 * Outcome of [RecordInventoryReceiptUseCase.execute] - a sealed
 * `Result`, mirroring [RecordSaleResult]/[RecordVendorObligationResult]
 * exactly.
 */
sealed class RecordInventoryReceiptResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordInventoryReceiptResult()
    data object InvalidAmount : RecordInventoryReceiptResult()
    data object PeriodNotFound : RecordInventoryReceiptResult()
    data object PeriodNotOpen : RecordInventoryReceiptResult()
    data class InventoryAssetAccountNotFound(val accountId: AccountId) : RecordInventoryReceiptResult()
    data class ContraAccountNotFound(val accountId: AccountId) : RecordInventoryReceiptResult()
}

/**
 * The *Record inventory receipt* thin posting interface - Inventory's
 * counterpart to [RecordSaleUseCase]/[RecordVendorObligationUseCase],
 * built once the Option A/B fork
 * (`docs/Ecosystem_Extraction_DDD_Design.md` Section 1.3) resolved as
 * **Option B**: the whole IAS 2 costing engine moved to `fish-
 * inventory-management` (IM), and the GL Engine's role narrowed to
 * recording the *committed cost* IM hands it, immutably, once. Dr
 * [Request.inventoryAssetAccountId], Cr [Request.contraAccountId] for a
 * caller-already-computed [Request.committedCost] - no weighted-average
 * blend, no `StockItem` lookup, no persisted inventory-valuation state
 * read on this side at all. Tagged `DimensionType.ITEM` with a
 * caller-supplied [StockItemId], reused purely as a non-authoritative
 * tag value (the same treatment [CustomerId]/[CreditorId] get on
 * [RecordSaleUseCase]/[RecordVendorObligationUseCase]) - IM computes
 * and owns the real item identity; this repo's `StockItemId` just gives
 * the tag type safety.
 *
 * **Additive, not a replacement.** `domain.inventory.StockItem`/
 * `PostInventoryReceiptUseCase` still exist here unchanged - the old
 * use case is still what `PurchaseOrder.send()`'s in-process
 * GOODS-line receipt relies on (`docs/Ecosystem_Extraction_DDD_Design.md`
 * Section 2's coupling problem, not yet resolved). This use case is
 * what IM is expected to call once its own application layer exists;
 * nothing in `fish-fish-gl-engine` is deleted by adding it, matching
 * the precedent [RecordSaleUseCase]/[RecordVendorObligationUseCase]
 * already set for SOP/POP.
 *
 * **No quantity field at all** - unlike [PostInventoryReceiptUseCase.Request],
 * which takes `quantityReceived`/`costReceived` (per-unit) and computes
 * the total itself via `StockItem.recordReceipt()`. Here IM has already
 * done that computation; this use case only ever records the resulting
 * committed [Money] figure, per the user's own framing: "The GL should
 * record what has been decided. It should not be doing the decision
 * making."
 */
class RecordInventoryReceiptUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val inventoryAssetAccountId: AccountId,
        val contraAccountId: AccountId,
        val committedCost: Money,
        val itemId: StockItemId,
        val description: String? = null
    )

    fun execute(request: Request): RecordInventoryReceiptResult {
        if (request.committedCost.amount.signum() <= 0) {
            return RecordInventoryReceiptResult.InvalidAmount
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordInventoryReceiptResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordInventoryReceiptResult.PeriodNotOpen
        }

        val inventoryAssetAccount = accountRepository.findById(request.inventoryAssetAccountId)
            ?: return RecordInventoryReceiptResult.InventoryAssetAccountNotFound(request.inventoryAssetAccountId)
        val contraAccount = accountRepository.findById(request.contraAccountId)
            ?: return RecordInventoryReceiptResult.ContraAccountNotFound(request.contraAccountId)

        val lines = listOf(
            JournalLine(
                inventoryAssetAccount.id, request.committedCost, TransactionSide.DEBIT,
                mapOf(DimensionType.ITEM to request.itemId.value.toString())
            ),
            JournalLine(contraAccount.id, request.committedCost, TransactionSide.CREDIT)
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.INTEGRATION, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordInventoryReceiptUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(inventoryAssetAccount, contraAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordInventoryReceiptResult.Success(entry, entry.pullDomainEvents())
    }
}
