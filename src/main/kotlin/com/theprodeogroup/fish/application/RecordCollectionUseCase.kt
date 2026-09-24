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
import com.theprodeogroup.fish.domain.sales.CustomerId
import java.time.LocalDate

/**
 * Outcome of [RecordCollectionUseCase.execute] - a sealed `Result`,
 * same reasoning as [RecordSaleResult].
 */
sealed class RecordCollectionResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordCollectionResult()
    data object InvalidAmount : RecordCollectionResult()
    data object PeriodNotFound : RecordCollectionResult()
    data object PeriodNotOpen : RecordCollectionResult()
    data class SettlementAccountNotFound(val accountId: AccountId) : RecordCollectionResult()
    data class ArControlAccountNotFound(val accountId: AccountId) : RecordCollectionResult()
}

/**
 * The *Record collection* thin posting interface
 * (docs/Sales_Order_Processing_DDD_Design.md Section 0/4) - Dr
 * caller-specified settlement account (Cash, or a facility/financing-
 * liability account per the Bill for Collection's mechanics), Cr AR
 * Control, tagged `DimensionType.CUSTOMER`. Called by SOP's
 * `BillForCollection.collect()` on confirmed collection - no owning
 * aggregate in this repo.
 *
 * The thin version of `Customer.receivePayment()`, which today (like
 * `Creditor.makePayment()` before Purchase Order Processing's own
 * extraction pass) has no application-layer caller wired through a
 * SalesOrder - `[[project_receivable_ecl]]` already flagged and fixed
 * a version of this gap once; this use case gives the corrected caller
 * a permanent home outside the GL Engine. Settlement line tagged
 * `CashFlowActivity.OPERATING` (IAS 7) - collecting a trade receivable
 * is always Operating, same rule `Customer.receivePayment()` already
 * applies, regardless of whether collection came via an ordinary
 * invoice or a Bill for Collection.
 */
class RecordCollectionUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val settlementAccountId: AccountId,
        val arControlAccountId: AccountId,
        val amount: Money,
        val customerId: CustomerId,
        val description: String? = null
    )

    fun execute(request: Request): RecordCollectionResult {
        if (request.amount.amount.signum() <= 0) {
            return RecordCollectionResult.InvalidAmount
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordCollectionResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordCollectionResult.PeriodNotOpen
        }

        // Cross-tenant isolation fix (2026-09-23, same gap/fix as PostJournalEntryUseCase):
        // each Account must belong to this Period's own Company, or it's treated as
        // AccountNotFound - never a distinct "forbidden" case, so this never confirms
        // another Company's Account exists.
        val settlementAccount = accountRepository.findById(request.settlementAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordCollectionResult.SettlementAccountNotFound(request.settlementAccountId)
        val arAccount = accountRepository.findById(request.arControlAccountId)
            ?.takeIf { it.companyId == period.companyId }
            ?: return RecordCollectionResult.ArControlAccountNotFound(request.arControlAccountId)

        val lines = listOf(
            JournalLine(
                settlementAccount.id, request.amount, TransactionSide.DEBIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.OPERATING.name)
            ),
            JournalLine(
                arAccount.id, request.amount, TransactionSide.CREDIT,
                mapOf(DimensionType.CUSTOMER to request.customerId.value.toString())
            )
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.INTEGRATION, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordCollectionUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(settlementAccount, arAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordCollectionResult.Success(entry, entry.pullDomainEvents())
    }
}
