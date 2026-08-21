package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import java.time.LocalDate
import java.util.UUID

/**
 * Identity of a BankStatementLine within a BankReconciliation.
 */
@JvmInline
value class BankStatementLineId(val value: UUID) {
    companion object {
        fun generate(): BankStatementLineId = BankStatementLineId(UUID.randomUUID())
    }
}

/**
 * One line from an external bank statement (docs/DDD_Design.md Section
 * 2.1's confirmed GL Engine scope) - a plain value the caller supplies,
 * however the data actually reached them (manual entry, file import,
 * Open Banking feed). No import/ingestion mechanism is built or
 * specified anywhere in the spec - out of scope for this domain-layer
 * increment, same as every other report in this codebase taking plain
 * in-memory data with no persistence/infrastructure layer yet.
 *
 * Uses [CashDirection] rather than raw `TransactionSide`, same
 * precedent as `CashBookEntry` - "money came in"/"money went out" is
 * how a bank statement actually reads, not debit/credit.
 */
data class BankStatementLine(
    val id: BankStatementLineId = BankStatementLineId.generate(),
    val date: LocalDate,
    val amount: Money,
    val direction: CashDirection,
    val description: String
)
