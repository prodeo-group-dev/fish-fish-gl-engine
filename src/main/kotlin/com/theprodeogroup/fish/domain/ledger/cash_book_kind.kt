package com.theprodeogroup.fish.domain.ledger

/**
 * What kind of cash-book account an ASSET [Account] is (docs/GL_Cash_And_Bank_Books_SRS.md,
 * Femi 2026-10-10: "cash and bank accounts, of which bank accounts have a further feature of
 * bank reconciliation, which also double as General Ledger accounts; they are books of original
 * entry"). The account stays an ordinary GL account - this only says it has a book.
 *
 * - [CASH]: a till, petty cash or imprest. Has a book; cannot be reconciled against a statement.
 * - [BANK]: has a book AND can be reconciled against a bank statement.
 *
 * Only an ASSET account may carry one. An account with no kind has no book.
 */
enum class CashBookKind {
    CASH,
    BANK
}
