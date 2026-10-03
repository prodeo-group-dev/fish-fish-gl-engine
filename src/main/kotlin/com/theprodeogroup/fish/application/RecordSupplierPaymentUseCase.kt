package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import java.time.LocalDate

/**
 * Outcome of [RecordSupplierPaymentUseCase.execute] - a sealed `Result`,
 * same reasoning as [RecordSupplierObligationResult].
 */
sealed class RecordSupplierPaymentResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordSupplierPaymentResult()
    data object InvalidAmount : RecordSupplierPaymentResult()
    data object PeriodNotFound : RecordSupplierPaymentResult()
    data object PeriodNotOpen : RecordSupplierPaymentResult()
    data class ApControlAccountNotFound(val accountId: AccountId) : RecordSupplierPaymentResult()
    data class SettlementAccountNotFound(val accountId: AccountId) : RecordSupplierPaymentResult()
}

/**
 * The *Record supplier payment* thin posting interface
 * (docs/Purchase_Order_Processing_DDD_Design.md Section 0/4) - the
 * Purchasing mirror of [RecordCollectionUseCase], and the thin version
 * of `Supplier.makePayment()`, which today has no application-layer
 * caller wired through a `PurchaseOrder` at all (confirmed by grep
 * during the design pass - it exists only as a domain method exercised
 * by tests). This use case finally gives it one, just not inside this
 * repo: called by POP's `SupplierPayment` (UC-PO6) once a payment is
 * confirmed - directly from Prodeo's own Cash/Bank, or from a
 * facility/financing-liability account, depending on [POP's `DealRole`/
 * `ExecutingParty`] (design doc Section 1) - **that role-driven account
 * selection happens entirely on POP's side**; this use case only ever
 * sees the already-resolved [settlementAccountId].
 *
 * Dr AP Control (decreasing the liability), Cr settlement account - the
 * mirror image of [RecordSupplierObligationUseCase]'s credit, matching
 * `Supplier.makePayment()`'s own KDoc: "Debit, not credit, on the AP
 * control line... a payment decreases a liability." Settlement line
 * tagged [CashFlowActivity.OPERATING] (IAS 7) - settling a payable is
 * always an Operating activity, same rule `Supplier.makePayment()`
 * already applies.
 */
class RecordSupplierPaymentUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val apControlAccountId: AccountId,
        val settlementAccountId: AccountId,
        val amount: Money,
        val supplierId: SupplierId,
        val description: String? = null
    )

    fun execute(request: Request): RecordSupplierPaymentResult {
        if (request.amount.amount.signum() <= 0) {
            return RecordSupplierPaymentResult.InvalidAmount
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordSupplierPaymentResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordSupplierPaymentResult.PeriodNotOpen
        }

        // Cross-tenant isolation fix (2026-09-23, same gap/fix as PostJournalEntryUseCase):
        // each Account must belong to this Period's own Company, or it's treated as
        // AccountNotFound - never a distinct "forbidden" case, so this never confirms
        // another Company's Account exists.
        val apControlAccount = accountRepository.findById(request.apControlAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordSupplierPaymentResult.ApControlAccountNotFound(request.apControlAccountId)
        val settlementAccount = accountRepository.findById(request.settlementAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordSupplierPaymentResult.SettlementAccountNotFound(request.settlementAccountId)

        val lines = listOf(
            JournalLine(
                apControlAccount.id, request.amount, TransactionSide.DEBIT,
                mapOf(DimensionType.VENDOR to request.supplierId.value.toString())
            ),
            JournalLine(
                settlementAccount.id, request.amount, TransactionSide.CREDIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.OPERATING.name)
            )
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.INTEGRATION, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordSupplierPaymentUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(apControlAccount, settlementAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordSupplierPaymentResult.Success(entry, entry.pullDomainEvents())
    }
}
