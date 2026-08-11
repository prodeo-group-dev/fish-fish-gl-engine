package com.theprodeogroup.fish.domain.ledger

/**
 * Plain-language direction for a CashBookEntry - "money came in" or
 * "money went out" (docs/DDD_Design.md Section 3.1). Deliberately not
 * `TransactionSide` directly: the whole point of `CashBookEntry` is that
 * a non-accountant user shouldn't have to know which side is debit and
 * which is credit (memory: feedback_non_accountant_ux).
 */
enum class CashDirection {
    RECEIVED,
    PAID
}
