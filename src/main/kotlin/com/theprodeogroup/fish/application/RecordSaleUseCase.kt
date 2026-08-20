package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.sales.CustomerId
import java.time.LocalDate

/**
 * Outcome of [RecordSaleUseCase.execute] - a sealed `Result`, same
 * reasoning as every other multi-reason posting use case this codebase
 * has built.
 */
sealed class RecordSaleResult {
    data class Success(
        val journalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : RecordSaleResult()
    data object InvalidAmount : RecordSaleResult()
    data object PeriodNotFound : RecordSaleResult()
    data object PeriodNotOpen : RecordSaleResult()
    data class ArControlAccountNotFound(val accountId: AccountId) : RecordSaleResult()
    data class RevenueAccountNotFound(val accountId: AccountId) : RecordSaleResult()
}

/**
 * The *Record sale* thin posting interface
 * (docs/Sales_Order_Processing_DDD_Design.md Section 0/4) - Dr AR
 * Control/Cr Revenue only, tagged `DimensionType.CUSTOMER` with a
 * caller-supplied [CustomerId]. Called by `fish-sales-order-processing`
 * (SOP) once it resolves its own contractually-correct revenue
 * recognition point (e.g. the CIF loading point, materially earlier
 * than physical goods issue for the sugar deal scenario) - **not**
 * driven by any aggregate in this repo. `domain.sales.Customer`/
 * `SalesOrder` still exist here unchanged (Section 6 - nothing in
 * `fish-fish-gl-engine` is deleted by this), this use case is
 * additive, coexisting with the old `PostSalesOrderUseCase`.
 *
 * Deliberately posts no COGS/Inventory lines - unlike today's
 * `SalesOrder.deliverLine()`, which bundles both into one compound
 * entry at the delivery moment. Once revenue recognition can fire
 * before physical goods issue, that bundling stops making sense:
 * Inventory Management's own `PostInventoryIssueUseCase` already
 * proves COGS/Inventory posting doesn't need a driving SalesOrder,
 * and fires independently whenever physical issue actually happens.
 *
 * [CustomerId] reused from `domain.sales` as a plain, opaque tag value
 * - the same non-authoritative-identifier treatment `EmployeeId` gets
 * on `LeaveAccrual` even though `Employee` lives entirely outside this
 * repo (design doc Section 5, item 8).
 */
class RecordSaleUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val arControlAccountId: AccountId,
        val revenueAccountId: AccountId,
        val amount: Money,
        val customerId: CustomerId,
        val description: String? = null
    )

    fun execute(request: Request): RecordSaleResult {
        if (request.amount.amount.signum() <= 0) {
            return RecordSaleResult.InvalidAmount
        }

        val period = periodRepository.findById(request.periodId)
            ?: return RecordSaleResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return RecordSaleResult.PeriodNotOpen
        }

        val arAccount = accountRepository.findById(request.arControlAccountId)
            ?: return RecordSaleResult.ArControlAccountNotFound(request.arControlAccountId)
        val revenueAccount = accountRepository.findById(request.revenueAccountId)
            ?: return RecordSaleResult.RevenueAccountNotFound(request.revenueAccountId)

        val lines = listOf(
            JournalLine(
                arAccount.id, request.amount, TransactionSide.DEBIT,
                mapOf(DimensionType.CUSTOMER to request.customerId.value.toString())
            ),
            JournalLine(revenueAccount.id, request.amount, TransactionSide.CREDIT)
        )
        val entry = JournalEntry.create(
            request.periodId, request.date, lines, JournalSource.INTEGRATION, request.description
        )
        val posting = entry.post()
        check(posting.isValid) {
            "RecordSaleUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        val touchedAccounts: List<Account> = listOf(arAccount, revenueAccount)
        for (account in touchedAccounts) {
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(entry)

        return RecordSaleResult.Success(entry, entry.pullDomainEvents())
    }
}
