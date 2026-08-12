package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import java.time.LocalDate

/**
 * A book of original entry for Cash/Bank movements (docs/DDD_Design.md
 * Section 3.1) - a simplified single-sided capture, not a new parallel
 * ledger. The person recording "money came in" or "money went out"
 * doesn't need to know which side is debit and which is credit;
 * [toJournalEntry] derives a properly balanced `JournalEntry` from it.
 *
 * Not a persisted aggregate - a lightweight command/DTO, per the "current
 * lean" already recorded in memory when this was designed. Uses
 * `JournalSource.MANUAL`: a real person initiated this even though the
 * UI simplifies the mechanics, which is the distinction that matters for
 * `JournalSource`'s existing values (human-initiated vs. system/API/
 * integration-initiated) - not worth a new enum value for this alone.
 *
 * [accountId] is assumed to always be an Asset-type Cash/Bank `Account` -
 * [direction] maps directly to [TransactionSide] on that assumption
 * (received increases an Asset, which is DEBIT; paid decreases it, which
 * is CREDIT, per `TransactionSide`'s own rule). This is not a generic
 * conversion for any Account type.
 *
 * [cashFlowActivity] tags the cash line for IAS 7 categorization
 * (`StatementOfCashFlows`), confirmed 2026-08-12. Defaults to
 * `CashFlowActivity.OPERATING` rather than being required - matches
 * the non-accountant-friendly, forgiving-data-entry principle this type
 * was already built around: most day-to-day cash-book entries genuinely
 * are routine operating movements, and a caller recording a capital
 * purchase, loan drawdown, or dividend can still override it.
 */
class CashBookEntry(
    val accountId: AccountId,
    val direction: CashDirection,
    val amount: Money,
    val counterAccountId: AccountId,
    val date: LocalDate,
    val cashFlowActivity: CashFlowActivity = CashFlowActivity.OPERATING,
    val description: String? = null
) {
    init {
        require(amount.amount.signum() > 0) {
            "CashBookEntry amount must be positive - use direction to indicate received vs. paid, not a negative amount"
        }
    }

    fun toJournalEntry(
        periodId: PeriodId,
        id: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry {
        val cashSide = when (direction) {
            CashDirection.RECEIVED -> TransactionSide.DEBIT
            CashDirection.PAID -> TransactionSide.CREDIT
        }
        val lines = listOf(
            JournalLine(
                accountId, amount, cashSide,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to cashFlowActivity.name)
            ),
            JournalLine(counterAccountId, amount, cashSide.opposite())
        )
        return JournalEntry.create(periodId, date, lines, JournalSource.MANUAL, description, id)
    }
}
