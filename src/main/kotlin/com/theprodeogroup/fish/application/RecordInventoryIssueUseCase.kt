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
 * Outcome of [RecordInventoryIssueUseCase.execute] - the mirror image
 * of [RecordInventoryReceiptResult].
 */
sealed class RecordInventoryIssueResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordInventoryIssueResult()
    data object InvalidAmount : RecordInventoryIssueResult()
    data object PeriodNotFound : RecordInventoryIssueResult()
    data object PeriodNotOpen : RecordInventoryIssueResult()
    data class InventoryAssetAccountNotFound(val accountId: AccountId) : RecordInventoryIssueResult()
    data class ContraAccountNotFound(val accountId: AccountId) : RecordInventoryIssueResult()
}

/**
 * The *Record inventory issue* thin posting interface - the mirror
 * image of [RecordInventoryReceiptUseCase], same Option B resolution
 * (`docs/Ecosystem_Extraction_DDD_Design.md` Section 1.3). Dr
 * [Request.contraAccountId] (typically COGS), Cr
 * [Request.inventoryAssetAccountId] for a caller-already-computed
 * [Request.committedCost] - IM has already read its own `Item.unitCost`
 * (weighted-average) before calling this; no cost computation happens
 * here.
 *
 * **Additive, not a replacement** - `PostInventoryIssueUseCase` still
 * exists unchanged, still what `SalesOrder.deliverLine()`'s in-process
 * GOODS-line issue relies on, same coexistence reasoning as
 * [RecordInventoryReceiptUseCase]'s own KDoc.
 */
class RecordInventoryIssueUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val contraAccountId: AccountId,
        val inventoryAssetAccountId: AccountId,
        val committedCost: Money,
        val itemId: StockItemId,
        val description: String? = null
    )

    fun execute(request: Request): RecordInventoryIssueResult {
        if (request.committedCost.amount.signum() <= 0) {
            return RecordInventoryIssueResult.InvalidAmount
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordInventoryIssueResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordInventoryIssueResult.PeriodNotOpen
        }

        // Cross-tenant isolation fix (2026-09-23, same gap/fix as PostJournalEntryUseCase):
        // each Account must belong to this Period's own Company, or it's treated as
        // AccountNotFound - never a distinct "forbidden" case, so this never confirms
        // another Company's Account exists.
        val contraAccount = accountRepository.findById(request.contraAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordInventoryIssueResult.ContraAccountNotFound(request.contraAccountId)
        val inventoryAssetAccount = accountRepository.findById(request.inventoryAssetAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordInventoryIssueResult.InventoryAssetAccountNotFound(request.inventoryAssetAccountId)

        val lines = listOf(
            JournalLine(contraAccount.id, request.committedCost, TransactionSide.DEBIT),
            JournalLine(
                inventoryAssetAccount.id, request.committedCost, TransactionSide.CREDIT,
                mapOf(DimensionType.ITEM to request.itemId.value.toString())
            )
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.INTEGRATION, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordInventoryIssueUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(contraAccount, inventoryAssetAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordInventoryIssueResult.Success(entry, entry.pullDomainEvents())
    }
}
