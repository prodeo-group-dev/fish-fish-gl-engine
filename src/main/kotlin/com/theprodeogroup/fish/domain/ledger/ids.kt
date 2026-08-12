package com.theprodeogroup.fish.domain.ledger

import java.util.UUID

/**
 * Identity of an Account aggregate.
 *
 * A typed wrapper around UUID so the compiler rejects an AccountId passed
 * where a CompanyId (or any other ID type) is expected.
 */
@JvmInline
value class AccountId(val value: UUID) {
    companion object {
        fun generate(): AccountId = AccountId(UUID.randomUUID())
    }
}

/**
 * Identity of a Period aggregate.
 */
@JvmInline
value class PeriodId(val value: UUID) {
    companion object {
        fun generate(): PeriodId = PeriodId(UUID.randomUUID())
    }
}

/**
 * Identity of a JournalEntry aggregate.
 */
@JvmInline
value class JournalEntryId(val value: UUID) {
    companion object {
        fun generate(): JournalEntryId = JournalEntryId(UUID.randomUUID())
    }
}

/**
 * Identity of a Prepayment aggregate.
 */
@JvmInline
value class PrepaymentId(val value: UUID) {
    companion object {
        fun generate(): PrepaymentId = PrepaymentId(UUID.randomUUID())
    }
}

/**
 * Identity of an AccruedExpense aggregate.
 */
@JvmInline
value class AccruedExpenseId(val value: UUID) {
    companion object {
        fun generate(): AccruedExpenseId = AccruedExpenseId(UUID.randomUUID())
    }
}

/**
 * Identity of a Borrowing aggregate.
 */
@JvmInline
value class BorrowingId(val value: UUID) {
    companion object {
        fun generate(): BorrowingId = BorrowingId(UUID.randomUUID())
    }
}
