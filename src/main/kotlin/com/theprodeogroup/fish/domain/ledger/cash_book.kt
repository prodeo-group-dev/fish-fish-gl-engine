package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * One row of a [CashBook]: a posted entry that touches the book's account, as the owner reads it -
 * [moneyIn] (a debit to the account) and [moneyOut] (a credit), the other account(s) it came from or
 * went to, and the running [balance] after it. A reversal is its own row, with [reversalOfEntryId] set.
 */
data class CashBookRow(
    val entryId: JournalEntryId,
    val periodId: PeriodId,
    val status: PostingStatus,
    val date: LocalDate,
    val description: String?,
    val source: JournalSource,
    val counterAccounts: List<Account>,
    val moneyIn: Money,
    val moneyOut: Money,
    val balance: Money,
    val reversalOfEntryId: JournalEntryId?,
    /** The entry that reversed this one, if any (found among the entries given, which include every entry touching the account). */
    val reversedByEntryId: JournalEntryId?
)

/**
 * The book of one cash or bank account over a date range (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB10,
 * Femi 2026-10-10: one book per account). A read-only view over the account's ordinary GL postings:
 * it never stores anything, so its closing balance is always the account's ledger balance at [to].
 *
 * Only entries with historical effect (POSTED, SYSTEM, REVERSED - the same rule the trial balance and
 * bank reconciliation use) appear; a draft or pending entry has not happened yet. Entries before [from]
 * form the [openingBalance]. Rows are in date order, and entries on the same date keep the order they
 * were given in. The account's own normal balance is a debit, so money in raises the balance.
 */
class CashBook private constructor(
    val account: Account,
    val from: LocalDate,
    val to: LocalDate,
    val currency: Currency,
    val openingBalance: Money,
    val rows: List<CashBookRow>,
    val totalMoneyIn: Money,
    val totalMoneyOut: Money,
    val closingBalance: Money
) {
    companion object {
        fun of(
            account: Account,
            entries: List<JournalEntry>,
            from: LocalDate,
            to: LocalDate,
            currency: Currency,
            accountsById: Map<AccountId, Account>
        ): CashBook {
            require(account.cashBookKind != null) { "Account ${account.code} is not a cash or bank account, so it has no book" }
            require(!to.isBefore(from)) { "A cash book range cannot end ($to) before it starts ($from)" }

            val zero = Money(BigDecimal.ZERO, currency)
            fun net(entry: JournalEntry): Money = entry.lines.filter { it.accountId == account.id }.fold(zero) { sum, line ->
                if (line.side == TransactionSide.DEBIT) sum + line.amount else sum - line.amount
            }

            val touching = entries.filter { it.status.hasHistoricalEffect() && it.lines.any { line -> line.accountId == account.id } }
            val opening = touching.filter { it.date.isBefore(from) }.fold(zero) { sum, entry -> sum + net(entry) }

            var running = opening
            var totalIn = zero
            var totalOut = zero
            val rows = touching
                .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
                .sortedBy { it.date }
                .map { entry ->
                    val accountLines = entry.lines.filter { it.accountId == account.id }
                    val moneyIn = accountLines.filter { it.side == TransactionSide.DEBIT }.fold(zero) { sum, line -> sum + line.amount }
                    val moneyOut = accountLines.filter { it.side == TransactionSide.CREDIT }.fold(zero) { sum, line -> sum + line.amount }
                    running = running + moneyIn - moneyOut
                    totalIn = totalIn + moneyIn
                    totalOut = totalOut + moneyOut
                    CashBookRow(
                        entryId = entry.id,
                        periodId = entry.periodId,
                        status = entry.status,
                        date = entry.date,
                        description = entry.description,
                        source = entry.source,
                        counterAccounts = entry.lines.map { it.accountId }.filter { it != account.id }.distinct()
                            .mapNotNull { accountsById[it] },
                        moneyIn = moneyIn,
                        moneyOut = moneyOut,
                        balance = running,
                        reversalOfEntryId = entry.reversalOfEntryId,
                        reversedByEntryId = touching.firstOrNull { it.reversalOfEntryId == entry.id }?.id
                    )
                }

            return CashBook(account, from, to, currency, opening, rows, totalIn, totalOut, running)
        }
    }
}
