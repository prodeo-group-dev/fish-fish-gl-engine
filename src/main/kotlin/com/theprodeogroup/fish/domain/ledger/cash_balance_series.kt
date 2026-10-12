package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.TransactionSide
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * The combined end-of-day balance of a Company's cash and bank books over the last [days] days, oldest first (the
 * Business Owner dashboard's cash trend; docs/ea-dashboard-playbook).
 *
 * One point per day from [from] to [to] inclusive ([to] is "today"), so there are exactly `days` points. A day with no
 * entries closes at the previous day's balance; everything dated before [from] is the opening balance. An entry counts
 * once, by the net of its lines on the given books (a transfer between two books therefore moves nothing), and only if
 * it has a historical effect (posted or reversed, like the cash book), so an undone entry and its reversal net to zero.
 * Entries dated after [to] and entries touching no book are ignored. Single currency, the Company's own.
 */
class CashBalanceSeries private constructor(
    val currency: Currency,
    val from: LocalDate,
    val to: LocalDate,
    val points: List<Point>
) {
    data class Point(val date: LocalDate, val closingBalance: Money)

    companion object {
        const val MIN_DAYS = 1
        const val MAX_DAYS = 366

        fun of(books: List<Account>, entries: List<JournalEntry>, to: LocalDate, days: Int, currency: Currency): CashBalanceSeries {
            require(days in MIN_DAYS..MAX_DAYS) { "days must be between $MIN_DAYS and $MAX_DAYS, got $days" }
            val from = to.minusDays((days - 1).toLong())
            val zero = Money(BigDecimal.ZERO, currency)
            val bookIds = books.map { it.id }.toSet()

            val movementByDate = entries
                .distinctBy { it.id }
                .filter { it.status.hasHistoricalEffect() && !it.date.isAfter(to) }
                .mapNotNull { entry ->
                    val touching = entry.lines.filter { it.accountId in bookIds }
                    if (touching.isEmpty()) null
                    else entry.date to touching.fold(zero) { sum, line ->
                        if (line.side == TransactionSide.DEBIT) sum + line.amount else sum - line.amount
                    }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, movements) -> movements.fold(zero) { sum, movement -> sum + movement } }

            var running = movementByDate.filterKeys { it.isBefore(from) }.values.fold(zero) { sum, movement -> sum + movement }
            val points = (0 until days).map { offset ->
                val date = from.plusDays(offset.toLong())
                running = running + (movementByDate[date] ?: zero)
                Point(date, running)
            }
            return CashBalanceSeries(currency, from, to, points)
        }
    }
}
