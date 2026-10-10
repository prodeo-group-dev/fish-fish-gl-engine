package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.hasHistoricalEffect
import java.math.BigDecimal
import java.util.Currency

/**
 * The balance of a cash or bank book today: every posted entry's lines on the account, money in (debit) less money out
 * (credit). The same figure the account has in the trial balance. Shared by the cash book use cases so there is one
 * definition.
 */
internal fun JournalEntryRepository.balanceOfBook(book: Account, currency: Currency): Money {
    val zero = Money(BigDecimal.ZERO, currency)
    return findAllByAccount(book.id)
        .filter { it.status.hasHistoricalEffect() }
        .flatMap { it.lines }
        .filter { it.accountId == book.id }
        .fold(zero) { sum, line -> if (line.side == TransactionSide.DEBIT) sum + line.amount else sum - line.amount }
}
